package com.wkrepo.system.service;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.PaidLeaveEntitlement;
import com.wkrepo.system.entity.PaidLeaveGrant;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.PaidLeaveEntitlementRepository;
import com.wkrepo.system.repository.PaidLeaveGrantRepository;
import com.wkrepo.system.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 年休の付与：入社日基準の自動付与（出勤率 8 割の判定を含む）、手動付与、旧方式からの移行、年 5 日取得義務。
 */
@Service
public class LeaveGrantService {

    private static final Logger logger = LoggerFactory.getLogger(LeaveGrantService.class);
    /** 自動付与のお知らせを送るのは、付与日がこの日数以内のものだけ（過去分をさかのぼって付与したときに大量に送らない） */
    private static final int NOTIFY_RECENT_DAYS = 31;
    static final BigDecimal MAX_MANUAL_DAYS = new BigDecimal("40");

    private final PaidLeaveGrantRepository grantRepository;
    private final PaidLeaveEntitlementRepository entitlementRepository;
    private final UserRepository userRepository;
    private final AttendanceService attendanceService;
    private final HolidayService holidayService;
    private final PaidLeaveService paidLeaveService;
    private final NotificationService notificationService;
    private final Clock clock;

    @Autowired
    public LeaveGrantService(PaidLeaveGrantRepository grantRepository,
                             PaidLeaveEntitlementRepository entitlementRepository, UserRepository userRepository,
                             AttendanceService attendanceService, HolidayService holidayService,
                             PaidLeaveService paidLeaveService, NotificationService notificationService) {
        this(grantRepository, entitlementRepository, userRepository, attendanceService, holidayService,
                paidLeaveService, notificationService, Clock.systemDefaultZone());
    }

    LeaveGrantService(PaidLeaveGrantRepository grantRepository,
                      PaidLeaveEntitlementRepository entitlementRepository, UserRepository userRepository,
                      AttendanceService attendanceService, HolidayService holidayService,
                      PaidLeaveService paidLeaveService, NotificationService notificationService, Clock clock) {
        this.grantRepository = grantRepository;
        this.entitlementRepository = entitlementRepository;
        this.userRepository = userRepository;
        this.attendanceService = attendanceService;
        this.holidayService = holidayService;
        this.paidLeaveService = paidLeaveService;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ===================== 自動付与 =====================

    /** 全員の自動付与を実行する。作成した付与の件数を返す */
    public int autoGrantAll() {
        int created = 0;
        for (User user : userRepository.findAll()) {
            if (!"INACTIVE".equals(user.getStatus()) && user.getHireDate() != null) {
                try {
                    created += autoGrant(user).size();
                } catch (RuntimeException e) {
                    logger.error("年休の自動付与でエラー userId={}", user.getId(), e);
                }
            }
        }
        if (created > 0) {
            logger.info("年休を自動付与しました：{} 件", created);
        }
        return created;
    }

    /**
     * 1 人分の自動付与。入社日から今日までの付与予定のうち、まだ時効になっておらず未付与のものを付与する
     * （同じ付与日の自動付与は 1 回だけ）。出勤率が 8 割未満の期間は付与せず、総務に知らせる。
     */
    @Transactional
    public List<PaidLeaveGrant> autoGrant(User user) {
        LocalDate today = today();
        List<PaidLeaveGrant> created = new ArrayList<>();
        for (LeaveGrantPolicy.Schedule schedule : LeaveGrantPolicy.schedulesUntil(user, today)) {
            if (schedule.expiryDate().isBefore(today)
                    || grantRepository.existsByUserIdAndGrantDateAndSource(
                            user.getId(), schedule.grantDate(), PaidLeaveGrant.SOURCE_AUTO)) {
                continue;
            }
            BigDecimal rate = attendanceRate(user, schedule.periodStart(), schedule.periodEnd());
            if (rate != null && rate.compareTo(LeaveGrantPolicy.REQUIRED_ATTENDANCE_RATE) < 0) {
                notifyRateTooLow(user, schedule, rate);
                continue;
            }
            PaidLeaveGrant grant = new PaidLeaveGrant();
            grant.setUser(user);
            grant.setGrantDate(schedule.grantDate());
            grant.setExpiryDate(schedule.expiryDate());
            grant.setGrantedDays(schedule.days());
            grant.setSource(PaidLeaveGrant.SOURCE_AUTO);
            grant.setAttendanceRate(rate);
            grant.setCreatedAt(LocalDateTime.now(clock));
            PaidLeaveGrant saved = grantRepository.save(grant);
            created.add(saved);
            if (ChronoUnit.DAYS.between(schedule.grantDate(), today) <= NOTIFY_RECENT_DAYS) {
                notificationService.notify(user, "LEAVE_GRANT", "notification.leaveGranted",
                        List.of(schedule.days().toPlainString(), schedule.grantDate().toString(),
                                schedule.expiryDate().toString()),
                        "/paid-leave", "LEAVE_GRANTED:" + schedule.grantDate());
            }
        }
        return created;
    }

    private void notifyRateTooLow(User user, LeaveGrantPolicy.Schedule schedule, BigDecimal rate) {
        logger.info("出勤率 {}% のため年休を付与しませんでした userId={}, grantDate={}", rate, user.getId(), schedule.grantDate());
        for (User soumu : soumuRecipients()) {
            notificationService.notify(soumu, "LEAVE_GRANT", "notification.leaveGrantSkipped",
                    List.of(user.getName(), schedule.grantDate().toString(), rate.toPlainString()),
                    "/leave-management/user?id=" + user.getId(),
                    "LEAVE_GRANT_SKIPPED:" + user.getId() + ":" + schedule.grantDate());
        }
    }

    /**
     * 出勤率（%）= 出勤した日 ÷ 全労働日。
     * <ul>
     *   <li>全労働日：期間内（今日まで）の勤務日（土日・祝日・会社休日を除く）。勤怠の記録が始まった日より前は数えない</li>
     *   <li>出勤した日：出社・在宅・半休に加え、法令・通達で出勤とみなす年休・特別休暇・振休・代休の日</li>
     *   <li>期間内に勤怠の記録が 1 件もない（システム導入前など）場合は null（判定できないため付与する）</li>
     * </ul>
     */
    public BigDecimal attendanceRate(User user, LocalDate from, LocalDate to) {
        LocalDate end = to.isAfter(today()) ? today() : to;
        if (end.isBefore(from)) {
            return null;
        }
        List<Attendance> records = attendanceService.getAttendancesByUserAndDateRange(user, from, end);
        LocalDate firstRecord = records.stream()
                .filter(record -> hasStatus(record.getAgreement36Status()))
                .map(Attendance::getDate)
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (firstRecord == null) {
            return null;
        }
        Set<LocalDate> attended = new HashSet<>();
        for (Attendance record : records) {
            String status = record.getAgreement36Status();
            if (AttendanceService.isWorkDayStatus(status) || AttendanceService.isPaidLeaveStatus(status)
                    || AttendanceService.STATUS_SPECIAL_LEAVE.equals(status)
                    || AttendanceService.isSubstituteStatus(status)) {
                attended.add(record.getDate());
            }
        }
        int workDays = 0;
        int attendedDays = 0;
        for (LocalDate date = firstRecord.isAfter(from) ? firstRecord : from; !date.isAfter(end); date = date.plusDays(1)) {
            if (!holidayService.isRestDay(date)) {
                workDays++;
                if (attended.contains(date)) {
                    attendedDays++;
                }
            }
        }
        if (workDays == 0) {
            return null;
        }
        return BigDecimal.valueOf(attendedDays * 100L)
                .divide(BigDecimal.valueOf(workDays), 1, RoundingMode.HALF_UP);
    }

    private static boolean hasStatus(String status) {
        return status != null && !status.isBlank() && !"-".equals(status.trim());
    }

    // ===================== 手動付与・削除 =====================

    /** 手動付与（特別付与・調整）。有効期限は付与日から 2 年 */
    @Transactional
    public PaidLeaveGrant addManualGrant(Long userId, LocalDate grantDate, BigDecimal days, String note) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user.error.notFound"));
        if (grantDate == null) {
            throw new IllegalArgumentException("leave.grant.error.dateRequired");
        }
        if (days == null || days.signum() <= 0 || days.compareTo(MAX_MANUAL_DAYS) > 0
                || days.remainder(new BigDecimal("0.5")).signum() != 0) {
            throw new BusinessException("leave.grant.error.invalidDays", MAX_MANUAL_DAYS.toPlainString());
        }
        PaidLeaveGrant grant = new PaidLeaveGrant();
        grant.setUser(user);
        grant.setGrantDate(grantDate);
        grant.setExpiryDate(LeaveGrantPolicy.expiryOf(grantDate));
        grant.setGrantedDays(days.setScale(1, RoundingMode.UNNECESSARY));
        grant.setSource(PaidLeaveGrant.SOURCE_MANUAL);
        grant.setNote(note == null || note.isBlank() ? null : note.strip().substring(0, Math.min(200, note.strip().length())));
        grant.setCreatedAt(LocalDateTime.now(clock));
        return grantRepository.save(grant);
    }

    /**
     * 付与の削除：手動付与・移行分のみ（自動付与は次回の実行で作り直されるため削除できない）。
     * 既に取得に充てている付与を消すと残日数が足りなくなるため、未使用のものに限る。
     */
    @Transactional
    public void deleteGrant(Long grantId) {
        PaidLeaveGrant grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new IllegalArgumentException("leave.grant.error.notFound"));
        if (PaidLeaveGrant.SOURCE_AUTO.equals(grant.getSource())) {
            throw new IllegalStateException("leave.grant.error.autoNotDeletable");
        }
        boolean used = paidLeaveService.ledger(grant.getUser()).grants().stream()
                .filter(usage -> usage.getGrant().getId() != null && usage.getGrant().getId().equals(grantId))
                .anyMatch(usage -> usage.getUsedDays().signum() > 0 || usage.getPendingDays().signum() > 0);
        if (used) {
            throw new IllegalStateException("leave.grant.error.inUse");
        }
        grantRepository.delete(grant);
    }

    /** 付与の履歴（付与日順）と使用状況 */
    public List<PaidLeaveLedger.GrantUsage> grantHistory(User user) {
        return paidLeaveService.ledger(user).grants();
    }

    // ===================== 旧方式からの移行 =====================

    /**
     * 旧方式（年ごとの付与日数）のデータを付与に移す：その年の 1 月 1 日付与、翌年末まで有効（2 年）。
     * 一度移したものは作らない。作成した件数を返す。
     */
    @Transactional
    public int migrateLegacyEntitlements() {
        int created = 0;
        for (PaidLeaveEntitlement entitlement : entitlementRepository.findAll()) {
            if (entitlement.getGrantedDays() == null || entitlement.getGrantedDays().signum() <= 0) {
                continue;
            }
            LocalDate grantDate = LocalDate.of(entitlement.getYear(), 1, 1);
            Long userId = entitlement.getUser().getId();
            if (grantRepository.existsByUserIdAndGrantDateAndSource(userId, grantDate, PaidLeaveGrant.SOURCE_MIGRATED)) {
                continue;
            }
            PaidLeaveGrant grant = new PaidLeaveGrant();
            grant.setUser(entitlement.getUser());
            grant.setGrantDate(grantDate);
            grant.setExpiryDate(LeaveGrantPolicy.expiryOf(grantDate));
            grant.setGrantedDays(entitlement.getGrantedDays().setScale(1, RoundingMode.HALF_UP));
            grant.setSource(PaidLeaveGrant.SOURCE_MIGRATED);
            grant.setNote(entitlement.getYear() + "年の付与日数から移行");
            grant.setCreatedAt(LocalDateTime.now(clock));
            grantRepository.save(grant);
            created++;
        }
        if (created > 0) {
            logger.info("旧方式の年休付与日数を {} 件、付与データに移行しました", created);
        }
        return created;
    }

    // ===================== 年 5 日取得義務 =====================

    public enum ObligationStatus {
        /** 5 日取得済み */
        DONE,
        /** 期間中（期限まで 3 か月より前） */
        IN_PROGRESS,
        /** 期限まで 3 か月以内で未達 */
        WARNING,
        /** 期限を過ぎて未達 */
        OVERDUE
    }

    /**
     * 年 5 日取得義務の状況（10 日以上の付与ごと）。期間は付与日から 1 年。
     *
     * @param remaining 期限までにあと何日取る必要があるか
     */
    public record Obligation(User user, PaidLeaveGrant grant, LocalDate deadline, BigDecimal taken,
                             BigDecimal remaining, long daysLeft, ObligationStatus status) {
    }

    /**
     * 現在の年 5 日取得義務（今日を含む期間、なければ直近 90 日以内に期限を過ぎた期間）。対象外なら null。
     */
    public Obligation currentObligation(User user) {
        LocalDate today = today();
        PaidLeaveGrant target = null;
        for (PaidLeaveGrant grant : grantRepository.findByUserIdOrderByGrantDateAscIdAsc(user.getId())) {
            if (grant.getGrantedDays().compareTo(LeaveGrantPolicy.OBLIGATION_THRESHOLD) < 0
                    || PaidLeaveGrant.SOURCE_MIGRATED.equals(grant.getSource())
                    || grant.getGrantDate().isAfter(today)) {
                continue;
            }
            LocalDate deadline = grant.getGrantDate().plusYears(1).minusDays(1);
            if (!deadline.isBefore(today.minusDays(90))) {
                target = grant; // 付与日順なので、条件に合う最新の付与が残る
            }
        }
        return target == null ? null : obligationOf(user, target);
    }

    Obligation obligationOf(User user, PaidLeaveGrant grant) {
        LocalDate today = today();
        LocalDate deadline = grant.getGrantDate().plusYears(1).minusDays(1);
        BigDecimal taken = paidLeaveService.takenDaysBetween(user, grant.getGrantDate(), deadline);
        BigDecimal remaining = LeaveGrantPolicy.OBLIGATION_DAYS.subtract(taken).max(BigDecimal.ZERO).setScale(1);
        long daysLeft = ChronoUnit.DAYS.between(today, deadline);
        ObligationStatus status;
        if (remaining.signum() == 0) {
            status = ObligationStatus.DONE;
        } else if (daysLeft < 0) {
            status = ObligationStatus.OVERDUE;
        } else if (daysLeft <= 90) {
            status = ObligationStatus.WARNING;
        } else {
            status = ObligationStatus.IN_PROGRESS;
        }
        return new Obligation(user, grant, deadline, taken, remaining, daysLeft, status);
    }

    /**
     * 年 5 日取得義務のリマインド：期限の 3 か月前・1 か月前に未達なら、本人・直属の上司・総務に知らせる（各 1 回）。
     */
    public int remindObligations() {
        int created = 0;
        List<User> soumu = soumuRecipients();
        for (User user : userRepository.findAll()) {
            if ("INACTIVE".equals(user.getStatus())) {
                continue;
            }
            Obligation obligation = currentObligation(user);
            if (obligation == null || obligation.status() != ObligationStatus.WARNING) {
                continue;
            }
            String stage = obligation.daysLeft() <= 30 ? "30" : "90";
            String dedupe = "LEAVE5:" + obligation.grant().getId() + ":" + stage;
            List<String> args = List.of(user.getName(), obligation.deadline().toString(),
                    obligation.taken().toPlainString(), obligation.remaining().toPlainString());
            if (notificationService.notify(user, "LEAVE5", "notification.leaveObligationSelf", args.subList(1, 4),
                    "/paid-leave", dedupe)) {
                created++;
            }
            Map<Long, User> others = new LinkedHashMap<>();
            if (user.getManagerId() != null) {
                userRepository.findById(user.getManagerId()).ifPresent(manager -> others.put(manager.getId(), manager));
            }
            soumu.forEach(s -> others.putIfAbsent(s.getId(), s));
            others.remove(user.getId());
            for (User other : others.values()) {
                if (notificationService.notify(other, "LEAVE5", "notification.leaveObligationMember", args,
                        "/leave-management", dedupe)) {
                    created++;
                }
            }
        }
        return created;
    }

    /** 総務への通知先：有効な総務。総務がいなければ管理者 */
    List<User> soumuRecipients() {
        List<User> all = userRepository.findAll();
        List<User> soumu = all.stream()
                .filter(user -> "SOUMU".equals(user.getRole()) && !"INACTIVE".equals(user.getStatus()))
                .toList();
        return !soumu.isEmpty() ? soumu : all.stream()
                .filter(user -> "ADMIN".equals(user.getRole()) && !"INACTIVE".equals(user.getStatus()))
                .toList();
    }
}
