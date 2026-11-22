package com.wkrepo.system.service;

import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveGrant;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.PaidLeaveGrantRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class PaidLeaveService {

    private static final BigDecimal HALF_DAY = new BigDecimal("0.5");
    private static final String FULL_DAY = "全日";
    /** 1 件の申請の最大期間（暦日）。入力ミスで何年分もの日付を数えないようにする */
    private static final long MAX_LEAVE_CALENDAR_DAYS = 366;

    private final PaidLeaveRepository paidLeaveRepository;
    private final PaidLeaveGrantRepository grantRepository;
    private final HolidayService holidayService;

    @Autowired
    public PaidLeaveService(
            PaidLeaveRepository paidLeaveRepository,
            PaidLeaveGrantRepository grantRepository,
            HolidayService holidayService) {
        this.paidLeaveRepository = paidLeaveRepository;
        this.grantRepository = grantRepository;
        this.holidayService = holidayService;
    }

    /**
     * 新規の年休申請（既存の申請を編集する機能はない）。状態は常に「申請中」から始める。
     * id や状態がリクエストから渡されても使わない（自分で承認済みにしたり他人の申請を上書きさせない）。
     * <ul>
     *   <li>日数は勤務日（土日・祝日・会社休日を除く）だけを数える。午前・午後休は 0.5 日</li>
     *   <li>拒否以外の既存の申請と日付が重なる申請はできない（同じ日の午前休と午後休は可）</li>
     *   <li>取得開始日に有効な付与の残り（承認済み・申請中を古い付与から充てた残り）を超える申請はできない</li>
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

    /**
     * 残日数のチェック：既存の申請（承認済み・申請中）と今回の申請を、取得開始日に有効な付与の古いものから充て、
     * 今回の申請が充てきれなければエラー（時効になった付与は使えない）。
     */
    private void ensureEnoughBalance(PaidLeave newLeave) {
        List<PaidLeaveGrant> grants = grantRepository.findByUserIdOrderByGrantDateAscIdAsc(newLeave.getUser().getId());
        List<PaidLeave> existing = activeLeaves(newLeave.getUser());
        BigDecimal available = PaidLeaveLedger.allocate(grants, existing)
                .grants().stream()
                .filter(usage -> usage.isValidOn(newLeave.getStartDate()))
                .map(PaidLeaveLedger.GrantUsage::getRemainingDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .max(BigDecimal.ZERO);
        List<PaidLeave> withNew = new ArrayList<>(existing);
        withNew.add(newLeave);
        // 新しい申請は「申請中」として充てる。取得開始日が既存の申請より前だと既存の申請の側が不足になることがあるため、
        // 不足の合計が増えるかどうかで判定する
        ApplicationStatus original = newLeave.getStatus();
        newLeave.setStatus(ApplicationStatus.PENDING);
        try {
            BigDecimal before = totalShortage(PaidLeaveLedger.allocate(grants, existing));
            BigDecimal after = totalShortage(PaidLeaveLedger.allocate(grants, withNew));
            if (after.compareTo(before) > 0) {
                throw new BusinessException("leave.application.insufficient",
                        available.setScale(1, RoundingMode.HALF_UP).toPlainString(),
                        newLeave.getLeaveDays().setScale(1, RoundingMode.HALF_UP).toPlainString());
            }
        } finally {
            newLeave.setStatus(original);
        }
    }

    private static BigDecimal totalShortage(PaidLeaveLedger.Result result) {
        return result.shortages().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 残日数の計算に使う申請（承認済み・申請中） */
    private List<PaidLeave> activeLeaves(User user) {
        return paidLeaveRepository.findByUserOrderByStartDateDesc(user).stream()
                .filter(leave -> PaidLeaveLedger.USED.contains(leave.getStatus())
                        || PaidLeaveLedger.PENDING.contains(leave.getStatus()))
                .toList();
    }

    /** 本人の年休台帳（付与ごとの使用状況） */
    @Transactional(readOnly = true)
    public PaidLeaveLedger.Result ledger(User user) {
        return PaidLeaveLedger.allocate(grantRepository.findByUserIdOrderByGrantDateAscIdAsc(user.getId()),
                activeLeaves(user));
    }

    /** 期間内（取得開始日で判定）に承認された年休の日数（年 5 日取得義務の集計用） */
    @Transactional(readOnly = true)
    public BigDecimal takenDaysBetween(User user, LocalDate from, LocalDate to) {
        return paidLeaveRepository.findByUserOrderByStartDateDesc(user).stream()
                .filter(leave -> PaidLeaveLedger.USED.contains(leave.getStatus()))
                .filter(leave -> leave.getStartDate() != null && leave.getLeaveDays() != null
                        && !leave.getStartDate().isBefore(from) && !leave.getStartDate().isAfter(to))
                .map(PaidLeave::getLeaveDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(1);
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

    /** 指定日に、指定した半休（午前 / 午後）のリーダー承認済みの年休があるか */
    public boolean isHalfDayLeaveApproved(User user, LocalDate date, String halfDayType) {
        return findLeavesCoveringDate(user, date).stream()
                .anyMatch(leave -> halfDayType.equals(leave.getLeaveType()) && isLeaderApproved(leave));
    }

    /** 指定日に、指定した半休（午前 / 午後）の申請中の年休があるか */
    public boolean hasPendingHalfDayLeave(User user, LocalDate date, String halfDayType) {
        return findLeavesCoveringDate(user, date).stream()
                .anyMatch(leave -> halfDayType.equals(leave.getLeaveType()) && isPendingApproval(leave));
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

    /**
     * 指定日時点の残日数：その日に有効な付与（時効になっていないもの）の合計と、そこに充てた承認済み・申請中の日数。
     * remainingDays = 付与 − 承認済み（申請中は pendingDays に別に出す）
     */
    @Transactional(readOnly = true)
    public Balance getBalance(User user, LocalDate asOf) {
        PaidLeaveLedger.Totals totals = ledger(user).totalsOn(asOf);
        return new Balance(totals.granted(), totals.used(), totals.remaining(), totals.pending());
    }

    public record Balance(BigDecimal grantedDays, BigDecimal usedDays,
                          BigDecimal remainingDays, BigDecimal pendingDays) {
        /** これから申請できる日数（残り − 申請中） */
        public BigDecimal availableDays() {
            return remainingDays.subtract(pendingDays).max(BigDecimal.ZERO);
        }
    }
}
