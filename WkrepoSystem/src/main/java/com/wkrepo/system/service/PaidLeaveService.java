package com.wkrepo.system.service;

import com.wkrepo.system.dto.PaidLeaveEntitlementSummary;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveEntitlement;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.PaidLeaveEntitlementRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional
public class PaidLeaveService {

    private static final BigDecimal MAX_ANNUAL_GRANT = new BigDecimal("365.0");
    private static final List<ApplicationStatus> USED_STATUSES = List.of(
            ApplicationStatus.APPROVED,
            ApplicationStatus.MANAGER_APPROVED,
            ApplicationStatus.FINALIZED);
    private static final List<ApplicationStatus> PENDING_STATUSES = List.of(
            ApplicationStatus.PENDING,
            ApplicationStatus.SUBMITTED);

    private static final BigDecimal HALF_DAY = new BigDecimal("0.5");
    private static final String FULL_DAY = "全日";
    /** 1 件の申請の最大期間（暦日）。入力ミスで何年分もの日付を数えないようにする */
    private static final long MAX_LEAVE_CALENDAR_DAYS = 366;

    private final PaidLeaveRepository paidLeaveRepository;
    private final PaidLeaveEntitlementRepository entitlementRepository;
    private final UserRepository userRepository;
    private final HolidayService holidayService;

    @Autowired
    public PaidLeaveService(
            PaidLeaveRepository paidLeaveRepository,
            PaidLeaveEntitlementRepository entitlementRepository,
            UserRepository userRepository,
            HolidayService holidayService) {
        this.paidLeaveRepository = paidLeaveRepository;
        this.entitlementRepository = entitlementRepository;
        this.userRepository = userRepository;
        this.holidayService = holidayService;
    }

    /**
     * 新規の年休申請（既存の申請を編集する機能はない）。状態は常に「申請中」から始める。
     * id や状態がリクエストから渡されても使わない（自分で承認済みにしたり他人の申請を上書きさせない）。
     * <ul>
     *   <li>日数は勤務日（土日・祝日・会社休日を除く）だけを数える。午前・午後休は 0.5 日</li>
     *   <li>拒否以外の既存の申請と日付が重なる申請はできない（同じ日の午前休と午後休は可）</li>
     *   <li>年ごとに「付与 − 取得済み − 申請中」を超える申請はできない</li>
     * </ul>
     * 例外のメッセージは messages*.properties のキー（引数があるものは BusinessException）。
     */
    public PaidLeave savePaidLeave(PaidLeave paidLeave) {
        if (paidLeave.getId() != null) {
            throw new IllegalArgumentException("application.error.invalidRequest");
        }
        BigDecimal days = previewLeaveDays(paidLeave.getStartDate(), paidLeave.getEndDate(), paidLeave.getLeaveType());
        if (days.signum() == 0) {
            throw new IllegalArgumentException("leave.application.noWorkingDay");
        }
        if (paidLeave.getEndDate() == null) {
            paidLeave.setEndDate(paidLeave.getStartDate());
        }
        paidLeave.setLeaveDays(days);

        ensureNoOverlap(paidLeave);
        ensureEnoughBalance(paidLeave);

        paidLeave.setStatus(ApplicationStatus.PENDING);
        return paidLeaveRepository.save(paidLeave);
    }

    /** 申請画面の「取得予定日数」表示用：勤務日だけを数えた日数（入力が不正なら例外） */
    public BigDecimal previewLeaveDays(LocalDate startDate, LocalDate endDate, String leaveType) {
        if (!FULL_DAY.equals(leaveType) && !isHalfDay(leaveType)) {
            throw new IllegalArgumentException("application.error.invalidRequest");
        }
        LocalDate end = endDate == null ? startDate : endDate;
        if (startDate == null || end.isBefore(startDate)
                || ChronoUnit.DAYS.between(startDate, end) >= MAX_LEAVE_CALENDAR_DAYS) {
            throw new IllegalArgumentException("leave.application.invalidDate");
        }
        if (isHalfDay(leaveType)) {
            if (!startDate.equals(end)) {
                throw new IllegalArgumentException("leave.application.halfDaySingleDate");
            }
            return holidayService.isRestDay(startDate) ? BigDecimal.ZERO.setScale(1) : HALF_DAY;
        }
        return BigDecimal.valueOf(countWorkingDays(startDate, end)).setScale(1, RoundingMode.UNNECESSARY);
    }

    public static boolean isHalfDay(String leaveType) {
        return "午前".equals(leaveType) || "午後".equals(leaveType);
    }

    /** 期間内の勤務日（土日・祝日・会社休日を除く）の日数 */
    long countWorkingDays(LocalDate startDate, LocalDate endDate) {
        long count = 0;
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (!holidayService.isRestDay(date)) {
                count++;
            }
        }
        return count;
    }

    /** 拒否以外の既存の申請と日付が重なっていないか（同じ日の午前休と午後休の組み合わせだけは可） */
    private void ensureNoOverlap(PaidLeave newLeave) {
        List<PaidLeave> overlapping = paidLeaveRepository.findByUserAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                newLeave.getUser(), newLeave.getEndDate(), newLeave.getStartDate());
        for (PaidLeave existing : overlapping) {
            if (existing.getStatus() == ApplicationStatus.REJECTED) {
                continue;
            }
            boolean morningAndAfternoon = isHalfDay(existing.getLeaveType()) && isHalfDay(newLeave.getLeaveType())
                    && !existing.getLeaveType().equals(newLeave.getLeaveType());
            if (!morningAndAfternoon) {
                throw new BusinessException("leave.application.overlap",
                        existing.getStartDate().toString(), existing.getEndDate().toString());
            }
        }
    }

    /** 年ごとに「付与 − 取得済み − 申請中」を超えていないか（年をまたぐ申請は年ごとに分けて確認） */
    private void ensureEnoughBalance(PaidLeave newLeave) {
        for (int year = newLeave.getStartDate().getYear(); year <= newLeave.getEndDate().getYear(); year++) {
            LocalDate yearStart = Year.of(year).atDay(1);
            LocalDate yearEnd = YearMonth.of(year, 12).atEndOfMonth();
            BigDecimal requested = daysWithinYear(newLeave, yearStart, yearEnd);
            if (requested == null || requested.signum() == 0) {
                continue;
            }
            Balance balance = getBalance(newLeave.getUser(), year);
            BigDecimal available = balance.remainingDays().subtract(balance.pendingDays()).max(BigDecimal.ZERO);
            if (requested.compareTo(available) > 0) {
                throw new BusinessException("leave.application.insufficient", String.valueOf(year),
                        available.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                        requested.setScale(1, RoundingMode.HALF_UP).toPlainString());
            }
        }
    }
    
    /**
     * 削除できるのは本人のみで、申請中または拒否された申請に限る（承認済みのものは残日数と勤怠の有給チェックに影響するため）。
     * 例外メッセージは messages*.properties のキー。
     */
    public void deletePaidLeave(Long id, User currentUser) {
        PaidLeave existing = paidLeaveRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("leave.application.notFound"));
        User owner = existing.getUser();
        if (owner == null || currentUser == null || !Objects.equals(owner.getId(), currentUser.getId())) {
            throw new SecurityException("leave.application.deleteNotOwner");
        }
        if (!isDeletable(existing)) {
            throw new IllegalStateException("leave.application.deleteNotAllowed");
        }
        paidLeaveRepository.delete(existing);
    }

    // paid-leave.html の削除ボタンも同じ判定を使う
    private boolean isDeletable(PaidLeave leave) {
        ApplicationStatus status = leave.getStatus();
        return status == null || status == ApplicationStatus.PENDING || status == ApplicationStatus.REJECTED;
    }
    
    public List<PaidLeave> getPaidLeavesByUser(User user) {
        return paidLeaveRepository.findByUserOrderByStartDateDesc(user);
    }
    
    public List<PaidLeave> getPaidLeavesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return paidLeaveRepository.findByUserAndStartDateBetweenOrderByStartDateAsc(user, startDate, endDate);
    }
    
    public PaidLeave getPaidLeaveById(Long id) {
        return paidLeaveRepository.findById(id).orElse(null);
    }
    
    public Double getTotalLeaveDaysByUserAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return paidLeaveRepository.getTotalLeaveDaysByUserAndDateRange(userId, startDate, endDate);
    }
    
    public void updateStatus(Long id, ApplicationStatus status) {
        PaidLeave pl = getPaidLeaveById(id);
        if (pl != null) {
            pl.setStatus(status);
            paidLeaveRepository.save(pl);
        }
    }

    public List<PaidLeave> findLeavesCoveringDate(User user, LocalDate date) {
        return paidLeaveRepository.findByUserAndStartDateLessThanEqualAndEndDateGreaterThanEqual(user, date, date);
    }

    /**
     * 指定日にリーダー承認済みの年休があるか
     */
    public boolean isPaidLeaveApproved(User user, LocalDate date) {
        return findLeavesCoveringDate(user, date).stream().anyMatch(this::isLeaderApproved);
    }

    /**
     * 指定日に未承認（申請中）の年休があるか
     */
    public boolean hasPendingPaidLeave(User user, LocalDate date) {
        return findLeavesCoveringDate(user, date).stream().anyMatch(this::isPendingApproval);
    }

    public boolean isLeaderApproved(PaidLeave paidLeave) {
        if (paidLeave == null || paidLeave.getStatus() == null) {
            return false;
        }
        ApplicationStatus status = paidLeave.getStatus();
        return status == ApplicationStatus.APPROVED || status == ApplicationStatus.MANAGER_APPROVED;
    }

    private boolean isPendingApproval(PaidLeave paidLeave) {
        if (paidLeave == null || paidLeave.getStatus() == null) {
            return false;
        }
        ApplicationStatus status = paidLeave.getStatus();
        return status == ApplicationStatus.PENDING || status == ApplicationStatus.SUBMITTED;
    }

    @Transactional(readOnly = true)
    public Balance getBalance(User user, int year) {
        BigDecimal granted = entitlementRepository.findByUserIdAndYear(user.getId(), year)
                .map(PaidLeaveEntitlement::getGrantedDays)
                .orElse(BigDecimal.ZERO);

        LocalDate yearStart = Year.of(year).atDay(1);
        LocalDate yearEnd = YearMonth.of(year, 12).atEndOfMonth();
        List<PaidLeave> approvedLeaves = paidLeaveRepository
                .findByUserAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        user, USED_STATUSES, yearEnd, yearStart);
        List<PaidLeave> pendingLeaves = paidLeaveRepository
                .findByUserAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        user, PENDING_STATUSES, yearEnd, yearStart);

        BigDecimal used = sumDaysForYear(approvedLeaves, yearStart, yearEnd);
        BigDecimal pending = sumDaysForYear(pendingLeaves, yearStart, yearEnd);
        return new Balance(granted, used, granted.subtract(used), pending);
    }

    public void setAnnualEntitlement(Long userId, int year, BigDecimal grantedDays) {
        if (grantedDays == null || grantedDays.compareTo(BigDecimal.ZERO) < 0
                || grantedDays.compareTo(MAX_ANNUAL_GRANT) > 0
                || grantedDays.stripTrailingZeros().scale() > 1) {
            throw new IllegalArgumentException("leave.entitlement.invalid");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("leave.entitlement.userNotFound"));
        PaidLeaveEntitlement entitlement = entitlementRepository.findByUserIdAndYear(userId, year)
                .orElseGet(PaidLeaveEntitlement::new);
        entitlement.setUser(user);
        entitlement.setYear(year);
        entitlement.setGrantedDays(grantedDays.setScale(1, RoundingMode.UNNECESSARY));
        entitlementRepository.save(entitlement);
    }

    @Transactional(readOnly = true)
    public List<PaidLeaveEntitlementSummary> getEntitlementsForYear(int year) {
        Map<Long, BigDecimal> grantsByUser = new HashMap<>();
        entitlementRepository.findByYear(year).forEach(entitlement ->
                grantsByUser.put(entitlement.getUser().getId(), entitlement.getGrantedDays()));

        List<PaidLeaveEntitlementSummary> summaries = new ArrayList<>();
        userRepository.findAll().stream()
                .filter(user -> !"INACTIVE".equals(user.getStatus()))
                .forEach(user -> summaries.add(new PaidLeaveEntitlementSummary(
                        user.getId(),
                        user.getEmployeeId(),
                        user.getName(),
                        user.getDepartment(),
                        grantsByUser.getOrDefault(user.getId(), BigDecimal.ZERO.setScale(1)))));
        summaries.sort(Comparator.comparing(PaidLeaveEntitlementSummary::getDepartment,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(PaidLeaveEntitlementSummary::getEmployeeId,
                        Comparator.nullsLast(String::compareTo)));
        return summaries;
    }

    private BigDecimal sumDaysForYear(List<PaidLeave> leaves, LocalDate yearStart, LocalDate yearEnd) {
        return leaves.stream()
                .map(leave -> daysWithinYear(leave, yearStart, yearEnd))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal daysWithinYear(PaidLeave leave, LocalDate yearStart, LocalDate yearEnd) {
        if (leave.getStartDate() == null || leave.getEndDate() == null
                || leave.getLeaveDays() == null || leave.getEndDate().isBefore(leave.getStartDate())) {
            return null;
        }
        LocalDate overlapStart = leave.getStartDate().isAfter(yearStart) ? leave.getStartDate() : yearStart;
        LocalDate overlapEnd = leave.getEndDate().isBefore(yearEnd) ? leave.getEndDate() : yearEnd;
        if (overlapEnd.isBefore(overlapStart)) {
            return null;
        }
        // 年内に収まる申請はそのままの日数（大半はこれ）
        if (overlapStart.equals(leave.getStartDate()) && overlapEnd.equals(leave.getEndDate())) {
            return leave.getLeaveDays();
        }
        // 年をまたぐ申請は勤務日の割合で按分する（勤務日がない場合だけ暦日の割合）。
        // 旧データ（暦日で数えた日数）も合計日数は変わらない
        long totalWorkingDays = countWorkingDays(leave.getStartDate(), leave.getEndDate());
        long workingDaysInYear = countWorkingDays(overlapStart, overlapEnd);
        long total = totalWorkingDays > 0 ? totalWorkingDays
                : ChronoUnit.DAYS.between(leave.getStartDate(), leave.getEndDate()) + 1;
        long inYear = totalWorkingDays > 0 ? workingDaysInYear
                : ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
        return leave.getLeaveDays()
                .multiply(BigDecimal.valueOf(inYear))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    public record Balance(BigDecimal grantedDays, BigDecimal usedDays,
                          BigDecimal remainingDays, BigDecimal pendingDays) {
    }
}
