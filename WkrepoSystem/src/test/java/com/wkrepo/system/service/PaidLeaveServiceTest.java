package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveGrant;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.PaidLeaveGrantRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 年休申請：日数（勤務日のみ）・重複・残日数（付与台帳）のチェック、削除は本人かつ 申請中 / 拒否 に限る */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaidLeaveServiceTest {

    @Mock
    private PaidLeaveRepository paidLeaveRepository;
    @Mock
    private PaidLeaveGrantRepository grantRepository;
    @Spy
    private HolidayService holidayService = TestData.holidayService(); // 祝日テーブルなし（計算式で判定）

    @InjectMocks
    private PaidLeaveService paidLeaveService;

    private final User owner = TestData.user(1L, "GENERAL", "开发部");
    private final User other = TestData.user(2L, "GENERAL", "开发部");
    private final List<PaidLeaveGrant> grants = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(paidLeaveRepository.save(any(PaidLeave.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(grantRepository.findByUserIdOrderByGrantDateAscIdAsc(owner.getId())).thenReturn(grants);
        grant("2025-10-01", "20");
    }

    // ---------- 申請：日数は勤務日だけ ----------

    @Test
    void countsOnlyWorkingDays() {
        // 2026/11/6(金) 〜 11/9(月)：土日を除いて 2 日
        PaidLeave saved = paidLeaveService.savePaidLeave(apply("2026-11-06", "2026-11-09", "全日"));

        assertThat(saved.getLeaveDays()).isEqualByComparingTo("2");
        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void holidaysAreNotCounted() {
        // 2026/11/2(月) 〜 11/4(水)：11/3 文化の日を除いて 2 日
        assertThat(paidLeaveService.savePaidLeave(apply("2026-11-02", "2026-11-04", "全日")).getLeaveDays())
                .isEqualByComparingTo("2");
    }

    @Test
    void halfDayIsHalf() {
        assertThat(paidLeaveService.savePaidLeave(apply("2026-11-02", null, "午前")).getLeaveDays())
                .isEqualByComparingTo("0.5");
    }

    @Test
    void restDaysOnlyIsRejected() {
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-07", "2026-11-08", "全日")))
                .hasMessage("leave.application.noWorkingDay");
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-07", null, "午後")))
                .hasMessage("leave.application.noWorkingDay");
        verify(paidLeaveRepository, never()).save(any());
    }

    @Test
    void invalidDatesAreRejected() {
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-10", "2026-11-09", "全日")))
                .hasMessage("leave.application.invalidDate");
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-09", "2026-11-10", "午前")))
                .hasMessage("leave.application.halfDaySingleDate");
    }

    @Test
    void unknownLeaveTypeIsRejected() {
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-09", null, "半休")))
                .hasMessage("application.error.invalidRequest");
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-09", null, null)))
                .hasMessage("application.error.invalidRequest");
        verify(paidLeaveRepository, never()).save(any());
    }

    @Test
    void newLeaveAlwaysStartsAsPendingEvenIfApprovedIsSent() {
        PaidLeave leave = apply("2026-11-02", null, "全日");
        leave.setStatus(ApplicationStatus.APPROVED);

        assertThat(paidLeaveService.savePaidLeave(leave).getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void leaveWithIdIsRejectedSoOthersLeaveCannotBeOverwritten() {
        PaidLeave leave = apply("2026-11-02", null, "全日");
        leave.setId(100L);

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(leave))
                .hasMessage("application.error.invalidRequest");
        verify(paidLeaveRepository, never()).save(any());
    }

    // ---------- 申請：日付の重複 ----------

    @Test
    void overlappingLeaveIsRejected() {
        existingLeaves(existing("2026-11-09", "2026-11-11", "全日", ApplicationStatus.PENDING));

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-11", "2026-11-12", "全日")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("leave.application.overlap");
    }

    @Test
    void rejectedLeaveDoesNotBlock() {
        existingLeaves(existing("2026-11-09", "2026-11-11", "全日", ApplicationStatus.REJECTED));

        assertThat(paidLeaveService.savePaidLeave(apply("2026-11-11", null, "全日"))).isNotNull();
    }

    @Test
    void morningAndAfternoonOnSameDayAreAllowedButNotTwoMornings() {
        existingLeaves(existing("2026-11-10", "2026-11-10", "午前", ApplicationStatus.APPROVED));

        assertThat(paidLeaveService.savePaidLeave(apply("2026-11-10", null, "午後"))).isNotNull();
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-10", null, "午前")))
                .hasMessage("leave.application.overlap");
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-10", null, "全日")))
                .hasMessage("leave.application.overlap");
    }

    // ---------- 申請：残日数（付与台帳） ----------

    @Test
    void exceedingBalanceIsRejected() {
        onlyGrant("2025-10-01", "1");

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-09", "2026-11-10", "全日")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("leave.application.insufficient")
                .satisfies(e -> assertThat(((BusinessException) e).getArgs()).containsExactly("1.0", "2.0"));
        verify(paidLeaveRepository, never()).save(any());
    }

    @Test
    void pendingLeaveIsDeductedFromAvailableDays() {
        onlyGrant("2025-10-01", "3");
        // 取得済み 1 日、申請中 1.5 日 → 申請できるのは残り 0.5 日
        existingLeaves(
                existing("2026-06-01", "2026-06-01", "全日", ApplicationStatus.APPROVED),
                existing("2026-07-01", "2026-07-01", "全日", ApplicationStatus.PENDING),
                existing("2026-07-02", "2026-07-02", "午前", ApplicationStatus.PENDING));

        assertThat(paidLeaveService.savePaidLeave(apply("2026-11-10", null, "午後"))).isNotNull();
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-11", null, "全日")))
                .hasMessage("leave.application.insufficient");
    }

    @Test
    void earlierLeaveCannotTakeDaysAlreadyReservedByLaterPendingLeave() {
        onlyGrant("2025-10-01", "1");
        existingLeaves(existing("2026-11-20", "2026-11-20", "全日", ApplicationStatus.PENDING));

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-10", null, "全日")))
                .hasMessage("leave.application.insufficient");
    }

    @Test
    void noGrantMeansNoLeave() {
        grants.clear();

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-10", null, "全日")))
                .hasMessage("leave.application.insufficient");
    }

    @Test
    void expiredGrantCannotBeUsed() {
        // 2024/4/1 付与は 2026/3/31 で時効
        onlyGrant("2024-04-01", "10");

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-10", null, "全日")))
                .hasMessage("leave.application.insufficient");
        assertThat(paidLeaveService.savePaidLeave(apply("2026-03-31", null, "全日"))).isNotNull();
    }

    @Test
    void grantCanBeUsedOnlyFromItsGrantDate() {
        onlyGrant("2026-12-01", "10");

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-30", null, "全日")))
                .hasMessage("leave.application.insufficient");
        assertThat(paidLeaveService.savePaidLeave(apply("2026-12-01", null, "全日"))).isNotNull();
    }

    @Test
    void olderGrantIsUsedFirstAndExpiredRemainderIsLost() {
        onlyGrant("2024-10-01", "5");   // 2026/9/30 で時効
        grant("2025-10-01", "10");
        existingLeaves(existing("2026-06-01", "2026-06-03", "全日", ApplicationStatus.APPROVED));

        PaidLeaveLedger.Result ledger = paidLeaveService.ledger(owner);
        assertThat(ledger.grants().get(0).getUsedDays()).isEqualByComparingTo("3");
        assertThat(ledger.grants().get(1).getUsedDays()).isEqualByComparingTo("0");

        // 9/30 時点：5 + 10 − 3 = 12 日、10/1 以降は古い付与の残り 2 日が時効で 10 日
        assertThat(paidLeaveService.getBalance(owner, LocalDate.of(2026, 9, 30)).remainingDays())
                .isEqualByComparingTo("12");
        PaidLeaveService.Balance after = paidLeaveService.getBalance(owner, LocalDate.of(2026, 10, 1));
        assertThat(after.grantedDays()).isEqualByComparingTo("10");
        assertThat(after.usedDays()).isEqualByComparingTo("0");
        assertThat(after.remainingDays()).isEqualByComparingTo("10");
    }

    @Test
    void takenDaysCountsOnlyApprovedLeavesStartingInPeriod() {
        existingLeaves(
                existing("2026-06-01", "2026-06-02", "全日", ApplicationStatus.APPROVED),
                existing("2026-07-01", "2026-07-01", "午前", ApplicationStatus.FINALIZED),
                existing("2026-08-01", "2026-08-01", "全日", ApplicationStatus.PENDING),
                existing("2025-09-30", "2025-09-30", "全日", ApplicationStatus.APPROVED));

        assertThat(paidLeaveService.takenDaysBetween(owner, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30)))
                .isEqualByComparingTo("2.5");
    }

    @Test
    void previewCountsWorkingDays() {
        assertThat(paidLeaveService.previewLeaveDays(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 9), "全日"))
                .isEqualByComparingTo("2");
        assertThat(paidLeaveService.previewLeaveDays(LocalDate.of(2026, 11, 7), null, "午前"))
                .isEqualByComparingTo("0");
    }

    // ---------- 削除 ----------

    @Test
    void ownerCanDeletePendingAndRejectedLeave() {
        PaidLeave pending = stored(ApplicationStatus.PENDING);
        paidLeaveService.deletePaidLeave(100L, owner);
        verify(paidLeaveRepository).delete(pending);

        PaidLeave rejected = stored(ApplicationStatus.REJECTED);
        paidLeaveService.deletePaidLeave(100L, owner);
        verify(paidLeaveRepository).delete(rejected);
    }

    @Test
    void otherUserCannotDelete() {
        stored(ApplicationStatus.PENDING);

        assertThatThrownBy(() -> paidLeaveService.deletePaidLeave(100L, other))
                .isInstanceOf(SecurityException.class);
        verify(paidLeaveRepository, never()).delete(any());
    }

    @Test
    void approvedLeaveCannotBeDeleted() {
        stored(ApplicationStatus.APPROVED);

        assertThatThrownBy(() -> paidLeaveService.deletePaidLeave(100L, owner))
                .isInstanceOf(IllegalStateException.class);
        verify(paidLeaveRepository, never()).delete(any());
    }

    @Test
    void missingLeaveIsReported() {
        when(paidLeaveRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paidLeaveService.deletePaidLeave(999L, owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- テストデータ ----------

    private PaidLeave apply(String start, String end, String type) {
        PaidLeave leave = new PaidLeave();
        leave.setUser(owner);
        leave.setStartDate(LocalDate.parse(start));
        leave.setEndDate(end == null ? null : LocalDate.parse(end));
        leave.setLeaveType(type);
        return leave;
    }

    private PaidLeave existing(String start, String end, String type, ApplicationStatus status) {
        PaidLeave leave = apply(start, end, type);
        leave.setLeaveDays(PaidLeaveService.isHalfDay(type) ? new BigDecimal("0.5")
                : BigDecimal.valueOf(LocalDate.parse(start).datesUntil(LocalDate.parse(end).plusDays(1)).count()));
        leave.setStatus(status);
        return leave;
    }

    /** 既存の申請：重複チェック（期間で検索）と残日数の計算（全件）の両方に返す */
    private void existingLeaves(PaidLeave... leaves) {
        when(paidLeaveRepository.findByUserAndStartDateLessThanEqualAndEndDateGreaterThanEqual(eq(owner), any(), any()))
                .thenAnswer(invocation -> {
                    LocalDate end = invocation.getArgument(1);
                    LocalDate start = invocation.getArgument(2);
                    return List.of(leaves).stream()
                            .filter(l -> !l.getStartDate().isAfter(end) && !l.getEndDate().isBefore(start))
                            .toList();
                });
        when(paidLeaveRepository.findByUserOrderByStartDateDesc(owner)).thenReturn(List.of(leaves));
    }

    private void grant(String grantDate, String days) {
        PaidLeaveGrant grant = new PaidLeaveGrant();
        grant.setId((long) grants.size() + 1);
        grant.setUser(owner);
        grant.setGrantDate(LocalDate.parse(grantDate));
        grant.setExpiryDate(LeaveGrantPolicy.expiryOf(LocalDate.parse(grantDate)));
        grant.setGrantedDays(new BigDecimal(days));
        grant.setSource(PaidLeaveGrant.SOURCE_AUTO);
        grants.add(grant);
    }

    private void onlyGrant(String grantDate, String days) {
        grants.clear();
        grant(grantDate, days);
    }

    private PaidLeave stored(ApplicationStatus status) {
        PaidLeave leave = new PaidLeave();
        leave.setUser(owner);
        leave.setStatus(status);
        when(paidLeaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        return leave;
    }
}
