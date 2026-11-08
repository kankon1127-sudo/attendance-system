package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveEntitlement;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.PaidLeaveEntitlementRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.UserRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 年休申請：日数（勤務日のみ）・重複・残日数のチェック、削除は本人かつ 申請中 / 拒否 に限る */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaidLeaveServiceTest {

    @Mock
    private PaidLeaveRepository paidLeaveRepository;
    @Mock
    private PaidLeaveEntitlementRepository entitlementRepository;
    @Mock
    private UserRepository userRepository;
    @Spy
    private HolidayService holidayService = TestData.holidayService(); // 祝日テーブルなし（計算式で判定）

    @InjectMocks
    private PaidLeaveService paidLeaveService;

    private final User owner = TestData.user(1L, "GENERAL", "开发部");
    private final User other = TestData.user(2L, "GENERAL", "开发部");

    @BeforeEach
    void setUp() {
        when(paidLeaveRepository.save(any(PaidLeave.class))).thenAnswer(invocation -> invocation.getArgument(0));
        grant(2026, "10");
        grant(2027, "10");
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

    // ---------- 申請：残日数 ----------

    @Test
    void exceedingBalanceIsRejected() {
        grant(2026, "1");

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-09", "2026-11-10", "全日")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("leave.application.insufficient");
        verify(paidLeaveRepository, never()).save(any());
    }

    @Test
    void pendingLeaveIsDeductedFromAvailableDays() {
        grant(2026, "3");
        // 取得済み 1 日、申請中 1.5 日 → 申請できるのは残り 0.5 日
        usedAndPending(2026,
                List.of(existing("2026-06-01", "2026-06-01", "全日", ApplicationStatus.APPROVED)),
                List.of(existing("2026-07-01", "2026-07-01", "全日", ApplicationStatus.PENDING),
                        existing("2026-07-02", "2026-07-02", "午前", ApplicationStatus.PENDING)));

        assertThat(paidLeaveService.savePaidLeave(apply("2026-11-10", null, "午後"))).isNotNull();
        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-11", null, "全日")))
                .hasMessage("leave.application.insufficient");
    }

    @Test
    void noEntitlementMeansNoLeave() {
        when(entitlementRepository.findByUserIdAndYear(owner.getId(), 2026)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-11-10", null, "全日")))
                .hasMessage("leave.application.insufficient");
    }

    @Test
    void leaveAcrossYearsIsCheckedPerYear() {
        // 2026/12/30(水) 〜 2027/1/5(火)：2026 年に 2 日（12/30, 12/31）、2027 年に 2 日（1/4, 1/5）
        // 2027 年の付与が 1 日しかないので不足（1/1 元日、1/2・1/3 は土日）
        grant(2027, "1");

        assertThatThrownBy(() -> paidLeaveService.savePaidLeave(apply("2026-12-30", "2027-01-05", "全日")))
                .hasMessage("leave.application.insufficient");

        grant(2027, "2");
        PaidLeave saved = paidLeaveService.savePaidLeave(apply("2026-12-30", "2027-01-05", "全日"));
        assertThat(saved.getLeaveDays()).isEqualByComparingTo("4");
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

    private void existingLeaves(PaidLeave... leaves) {
        when(paidLeaveRepository.findByUserAndStartDateLessThanEqualAndEndDateGreaterThanEqual(eq(owner), any(), any()))
                .thenReturn(List.of(leaves));
    }

    private void usedAndPending(int year, List<PaidLeave> used, List<PaidLeave> pending) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        when(paidLeaveRepository.findByUserAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                eq(owner), anyList(), eq(yearEnd), eq(yearStart))).thenAnswer(invocation -> {
                    List<ApplicationStatus> statuses = invocation.getArgument(1);
                    return statuses.contains(ApplicationStatus.APPROVED) ? used : pending;
                });
    }

    private void grant(int year, String days) {
        PaidLeaveEntitlement entitlement = new PaidLeaveEntitlement();
        entitlement.setUser(owner);
        entitlement.setYear(year);
        entitlement.setGrantedDays(new BigDecimal(days));
        when(entitlementRepository.findByUserIdAndYear(owner.getId(), year)).thenReturn(Optional.of(entitlement));
    }

    private PaidLeave stored(ApplicationStatus status) {
        PaidLeave leave = new PaidLeave();
        leave.setUser(owner);
        leave.setStatus(status);
        when(paidLeaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        return leave;
    }
}
