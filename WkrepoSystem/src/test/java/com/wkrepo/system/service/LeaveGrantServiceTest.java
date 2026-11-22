package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveEntitlement;
import com.wkrepo.system.entity.PaidLeaveGrant;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.PaidLeaveEntitlementRepository;
import com.wkrepo.system.repository.PaidLeaveGrantRepository;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 年休の自動付与（出勤率 8 割・冪等性）、手動付与と削除、旧データ移行、年 5 日取得義務とリマインド */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveGrantServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    @Mock
    private PaidLeaveGrantRepository grantRepository;
    @Mock
    private PaidLeaveEntitlementRepository entitlementRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AttendanceService attendanceService;
    @Mock
    private PaidLeaveService paidLeaveService;
    @Mock
    private NotificationService notificationService;

    private LeaveGrantService service;
    private final User employee = TestData.user(3L, "GENERAL", "開発部");
    private final User manager = TestData.user(2L, "MANAGER", "開発部");
    private final User soumu = TestData.user(5L, "SOUMU", "総務部");
    private final List<Attendance> attendances = new ArrayList<>();
    private final List<PaidLeaveGrant> grants = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        service = new LeaveGrantService(grantRepository, entitlementRepository, userRepository, attendanceService,
                TestData.holidayService(), paidLeaveService, notificationService, clock);
        employee.setManagerId(manager.getId());
        when(userRepository.findAll()).thenReturn(List.of(employee, manager, soumu));
        when(userRepository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(grantRepository.save(any(PaidLeaveGrant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(grantRepository.findByUserIdOrderByGrantDateAscIdAsc(employee.getId())).thenReturn(grants);
        when(attendanceService.getAttendancesByUserAndDateRange(eq(employee), any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(1);
            LocalDate to = invocation.getArgument(2);
            return attendances.stream().filter(a -> !a.getDate().isBefore(from) && !a.getDate().isAfter(to)).toList();
        });
        when(notificationService.notify(any(), anyString(), anyString(), anyList(), anyString(), anyString()))
                .thenReturn(true);
    }

    // ---------- 自動付与 ----------

    @Test
    void grantsUnexpiredSchedulesWhenNoAttendanceRecords() {
        // 入社 2024/5/1 → 2024/11/1（時効済み）・2025/11/1（11 日）・2026/11/1（12 日）
        employee.setHireDate(LocalDate.of(2024, 5, 1));

        List<PaidLeaveGrant> created = service.autoGrant(employee);

        assertThat(created).extracting(PaidLeaveGrant::getGrantDate)
                .containsExactly(LocalDate.of(2025, 11, 1), LocalDate.of(2026, 11, 1));
        assertThat(created).extracting(g -> g.getGrantedDays().intValue()).containsExactly(11, 12);
        assertThat(created.get(1).getExpiryDate()).isEqualTo(LocalDate.of(2028, 10, 31));
        assertThat(created).allMatch(g -> PaidLeaveGrant.SOURCE_AUTO.equals(g.getSource()) && g.getAttendanceRate() == null);
        // お知らせは直近 31 日以内の付与だけ
        verify(notificationService, times(1)).notify(eq(employee), eq("LEAVE_GRANT"), eq("notification.leaveGranted"),
                eq(List.of("12.0", "2026-11-01", "2028-10-31")), eq("/paid-leave"), eq("LEAVE_GRANTED:2026-11-01"));
    }

    @Test
    void autoGrantIsIdempotent() {
        employee.setHireDate(LocalDate.of(2024, 5, 1));
        when(grantRepository.existsByUserIdAndGrantDateAndSource(eq(employee.getId()), any(), eq(PaidLeaveGrant.SOURCE_AUTO)))
                .thenReturn(true);

        assertThat(service.autoGrant(employee)).isEmpty();
        verify(grantRepository, never()).save(any());
    }

    @Test
    void lowAttendanceRateSkipsGrantAndNotifiesSoumu() {
        employee.setHireDate(LocalDate.of(2024, 5, 1));
        // 2026/10 の勤怠：出社 1 日、残りは欠勤 → 2026/11/1 付与分（期間 2025/11/1〜2026/10/31）は 8 割未満
        attendances.add(TestData.attendance(employee, "2026-10-01", "出社", "09:00", "18:00", "01:00"));
        attendances.add(TestData.attendance(employee, "2026-10-02", "欠勤", null, null, null));

        List<PaidLeaveGrant> created = service.autoGrant(employee);

        assertThat(created).extracting(PaidLeaveGrant::getGrantDate).containsExactly(LocalDate.of(2025, 11, 1));
        ArgumentCaptor<List<String>> args = ArgumentCaptor.captor();
        verify(notificationService).notify(eq(soumu), eq("LEAVE_GRANT"), eq("notification.leaveGrantSkipped"),
                args.capture(), eq("/leave-management/user?id=3"), eq("LEAVE_GRANT_SKIPPED:3:2026-11-01"));
        assertThat(args.getValue().get(0)).isEqualTo(employee.getName());
        assertThat(new BigDecimal(args.getValue().get(2))).isLessThan(new BigDecimal("80"));
    }

    @Test
    void autoGrantAllSkipsInactiveAndNoHireDate() {
        employee.setHireDate(LocalDate.of(2024, 5, 1));
        manager.setHireDate(LocalDate.of(2020, 1, 1));
        manager.setStatus("INACTIVE");

        assertThat(service.autoGrantAll()).isEqualTo(2);
        verify(grantRepository, never()).existsByUserIdAndGrantDateAndSource(eq(manager.getId()), any(), any());
    }

    @Test
    void attendanceRateCountsLeaveDaysAsAttended() {
        // 2026/11/2(月)〜11/6(金)：11/3 は祝日なので全労働日 4 日。出社・有給・振休が出勤扱い、欠勤のみ欠勤
        attendances.add(TestData.attendance(employee, "2026-11-02", "出社", "09:00", "18:00", "01:00"));
        attendances.add(TestData.attendance(employee, "2026-11-04", "有給", null, null, null));
        attendances.add(TestData.attendance(employee, "2026-11-05", "振休", null, null, null));
        attendances.add(TestData.attendance(employee, "2026-11-06", "欠勤", null, null, null));

        assertThat(service.attendanceRate(employee, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6)))
                .isEqualByComparingTo("75.0");
        // 勤怠の記録が始まる前の日は数えない
        assertThat(service.attendanceRate(employee, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 6)))
                .isEqualByComparingTo("75.0");
    }

    @Test
    void attendanceRateStopsAtTodayAndIsNullWithoutRecords() {
        for (int day = 16; day <= 20; day++) {
            attendances.add(TestData.attendance(employee, "2026-11-" + day, "在宅", "09:00", "18:00", "01:00"));
        }

        assertThat(service.attendanceRate(employee, LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 30)))
                .isEqualByComparingTo("100.0");
        assertThat(service.attendanceRate(employee, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))).isNull();
    }

    // ---------- 手動付与・削除 ----------

    @Test
    void manualGrantValidatesDays() {
        assertThatThrownBy(() -> service.addManualGrant(employee.getId(), TODAY, new BigDecimal("0.3"), null))
                .isInstanceOf(BusinessException.class).hasMessage("leave.grant.error.invalidDays");
        assertThatThrownBy(() -> service.addManualGrant(employee.getId(), TODAY, new BigDecimal("40.5"), null))
                .hasMessage("leave.grant.error.invalidDays");
        assertThatThrownBy(() -> service.addManualGrant(employee.getId(), null, BigDecimal.ONE, null))
                .hasMessage("leave.grant.error.dateRequired");

        PaidLeaveGrant grant = service.addManualGrant(employee.getId(), TODAY, new BigDecimal("2.5"), " 特別付与 ");
        assertThat(grant.getSource()).isEqualTo(PaidLeaveGrant.SOURCE_MANUAL);
        assertThat(grant.getGrantedDays()).isEqualByComparingTo("2.5");
        assertThat(grant.getExpiryDate()).isEqualTo(LocalDate.of(2028, 11, 21));
        assertThat(grant.getNote()).isEqualTo("特別付与");
    }

    @Test
    void autoGrantCannotBeDeleted() {
        PaidLeaveGrant auto = grant(10L, "2025-10-01", "11", PaidLeaveGrant.SOURCE_AUTO);
        when(grantRepository.findById(10L)).thenReturn(Optional.of(auto));

        assertThatThrownBy(() -> service.deleteGrant(10L)).hasMessage("leave.grant.error.autoNotDeletable");
        verify(grantRepository, never()).delete(any());
    }

    @Test
    void usedManualGrantCannotBeDeletedButUnusedCan() {
        PaidLeaveGrant manual = grant(11L, "2026-11-01", "3", PaidLeaveGrant.SOURCE_MANUAL);
        when(grantRepository.findById(11L)).thenReturn(Optional.of(manual));
        PaidLeave leave = new PaidLeave();
        leave.setStartDate(LocalDate.of(2026, 11, 10));
        leave.setLeaveDays(BigDecimal.ONE);
        leave.setStatus(ApplicationStatus.PENDING);
        when(paidLeaveService.ledger(employee)).thenReturn(PaidLeaveLedger.allocate(List.of(manual), List.of(leave)));

        assertThatThrownBy(() -> service.deleteGrant(11L)).hasMessage("leave.grant.error.inUse");

        when(paidLeaveService.ledger(employee)).thenReturn(PaidLeaveLedger.allocate(List.of(manual), List.of()));
        service.deleteGrant(11L);
        verify(grantRepository).delete(manual);
    }

    // ---------- 旧データ移行 ----------

    @Test
    void legacyEntitlementsAreMigratedOnce() {
        PaidLeaveEntitlement entitlement = new PaidLeaveEntitlement();
        entitlement.setUser(employee);
        entitlement.setYear(2026);
        entitlement.setGrantedDays(new BigDecimal("10"));
        PaidLeaveEntitlement zero = new PaidLeaveEntitlement();
        zero.setUser(employee);
        zero.setYear(2025);
        zero.setGrantedDays(BigDecimal.ZERO);
        when(entitlementRepository.findAll()).thenReturn(List.of(entitlement, zero));

        assertThat(service.migrateLegacyEntitlements()).isEqualTo(1);
        ArgumentCaptor<PaidLeaveGrant> saved = ArgumentCaptor.forClass(PaidLeaveGrant.class);
        verify(grantRepository).save(saved.capture());
        assertThat(saved.getValue().getGrantDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(saved.getValue().getExpiryDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(saved.getValue().getSource()).isEqualTo(PaidLeaveGrant.SOURCE_MIGRATED);

        when(grantRepository.existsByUserIdAndGrantDateAndSource(employee.getId(), LocalDate.of(2026, 1, 1),
                PaidLeaveGrant.SOURCE_MIGRATED)).thenReturn(true);
        assertThat(service.migrateLegacyEntitlements()).isZero();
    }

    // ---------- 年 5 日取得義務 ----------

    @Test
    void obligationStatusDependsOnTakenDaysAndDeadline() {
        // 2026/1/15 付与 → 期限 2027/1/14（あと 53 日）
        grants.add(grant(20L, "2026-01-15", "10", PaidLeaveGrant.SOURCE_AUTO));
        taken("2026-01-15", "2");

        LeaveGrantService.Obligation obligation = service.currentObligation(employee);
        assertThat(obligation.status()).isEqualTo(LeaveGrantService.ObligationStatus.WARNING);
        assertThat(obligation.deadline()).isEqualTo(LocalDate.of(2027, 1, 14));
        assertThat(obligation.remaining()).isEqualByComparingTo("3");
        assertThat(obligation.daysLeft()).isEqualTo(53);

        taken("2026-01-15", "5");
        assertThat(service.currentObligation(employee).status()).isEqualTo(LeaveGrantService.ObligationStatus.DONE);
    }

    @Test
    void obligationInProgressAndOverdue() {
        grants.add(grant(21L, "2025-10-01", "11", PaidLeaveGrant.SOURCE_AUTO));  // 期限 2026/9/30（53 日前）
        taken("2025-10-01", "3");
        assertThat(service.currentObligation(employee).status()).isEqualTo(LeaveGrantService.ObligationStatus.OVERDUE);

        grants.add(grant(22L, "2026-10-01", "12", PaidLeaveGrant.SOURCE_AUTO));  // 新しい期間が優先
        taken("2026-10-01", "0");
        assertThat(service.currentObligation(employee).status()).isEqualTo(LeaveGrantService.ObligationStatus.IN_PROGRESS);
    }

    @Test
    void smallMigratedOrOldGrantsHaveNoObligation() {
        grants.add(grant(23L, "2026-04-01", "7", PaidLeaveGrant.SOURCE_AUTO));        // 10 日未満
        grants.add(grant(24L, "2026-01-01", "20", PaidLeaveGrant.SOURCE_MIGRATED));   // 移行分は期間が不明
        grants.add(grant(25L, "2025-04-01", "10", PaidLeaveGrant.SOURCE_AUTO));       // 期限から 90 日超

        assertThat(service.currentObligation(employee)).isNull();
    }

    @Test
    void remindersGoToEmployeeManagerAndSoumuOnce() {
        grants.add(grant(20L, "2026-01-15", "10", PaidLeaveGrant.SOURCE_AUTO));
        taken("2026-01-15", "2");

        assertThat(service.remindObligations()).isEqualTo(3);

        verify(notificationService).notify(eq(employee), eq("LEAVE5"), eq("notification.leaveObligationSelf"),
                eq(List.of("2027-01-14", "2.0", "3.0")), eq("/paid-leave"), eq("LEAVE5:20:90"));
        List<String> memberArgs = List.of(employee.getName(), "2027-01-14", "2.0", "3.0");
        verify(notificationService).notify(eq(manager), eq("LEAVE5"), eq("notification.leaveObligationMember"),
                eq(memberArgs), eq("/leave-management"), eq("LEAVE5:20:90"));
        verify(notificationService).notify(eq(soumu), eq("LEAVE5"), eq("notification.leaveObligationMember"),
                eq(memberArgs), eq("/leave-management"), eq("LEAVE5:20:90"));
    }

    // ---------- テストデータ ----------

    private PaidLeaveGrant grant(Long id, String grantDate, String days, String source) {
        PaidLeaveGrant grant = new PaidLeaveGrant();
        grant.setId(id);
        grant.setUser(employee);
        grant.setGrantDate(LocalDate.parse(grantDate));
        grant.setExpiryDate(LeaveGrantPolicy.expiryOf(LocalDate.parse(grantDate)));
        grant.setGrantedDays(new BigDecimal(days));
        grant.setSource(source);
        return grant;
    }

    private void taken(String grantDate, String days) {
        LocalDate from = LocalDate.parse(grantDate);
        when(paidLeaveService.takenDaysBetween(employee, from, from.plusYears(1).minusDays(1)))
                .thenReturn(new BigDecimal(days).setScale(1));
    }
}
