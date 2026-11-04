package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.AttendanceRepository;
import com.wkrepo.system.repository.MonthlyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceServiceTest {

    @Mock
    private AttendanceRepository attendanceRepository;
    @Mock
    private MonthlyReportRepository monthlyReportRepository;
    @Mock
    private PaidLeaveService paidLeaveService;
    @Spy
    private HolidayService holidayService = new HolidayService();

    @InjectMocks
    private AttendanceService attendanceService;

    private final User user = TestData.user(1L, "GENERAL", "开发部");

    @BeforeEach
    void setUp() {
        when(attendanceRepository.findByUserAndDate(any(), any())).thenReturn(Optional.empty());
        when(attendanceRepository.save(any(Attendance.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(monthlyReportRepository.findByUserIdAndYearAndMonth(anyLong(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
    }

    // ---------- 実働時間の計算（すべての保存経路が updateAttendance → calculateWorkHours を通る） ----------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // 説明,                         日付,        区分, 出勤,  退勤,  休憩,  期待実働, 期待残業
            "'平日 9-18 休1h',              2026-11-02, 出社, 09:00, 18:00, 01:00, 8.00, 0.00",
            "'平日 9-21 休1h 有加班',        2026-11-02, 在宅, 09:00, 21:00, 01:00, 11.00, 3.00",
            "'周日 9-19 休1h 全部算加班',    2026-11-01, 出社, 09:00, 19:00, 01:00, 9.00, 9.00",
            "'文化の日 9-12 全部算加班',     2026-11-03, 出社, 09:00, 12:00, 00:00, 3.00, 3.00",
            "'跨零点 22-06 休1h',            2026-11-05, 出社, 22:00, 06:00, 01:00, 7.00, 0.00",
            "'休息超过在岗时间',             2026-11-10, 出社, 09:00, 10:00, 02:00, 0.00, 0.00",
            "'没有休息时间',                 2026-11-02, 出社, 09:00, 17:30, ,      8.50, 0.50",
            "'有給 即使有时间也为 0',        2026-11-04, 有給, 09:00, 18:00, 01:00, 0.00, 0.00",
            "'欠勤 为 0',                    2026-11-04, 欠勤, 09:00, 18:00, 01:00, 0.00, 0.00",
            "'区分为 - 为 0',                2026-11-04, -,    09:00, 18:00, 01:00, 0.00, 0.00",
    })
    void calculatesWorkAndOvertimeHours(String description, String date, String status,
                                        String start, String end, String breakTime,
                                        String expectedWork, String expectedOvertime) {
        Attendance attendance = TestData.attendance(user, date, status, start, end, breakTime);

        Attendance saved = attendanceService.updateAttendance(attendance);

        assertThat(saved.getWorkHours()).isEqualByComparingTo(expectedWork);
        assertThat(saved.getOvertimeHours()).isEqualByComparingTo(expectedOvertime);
    }

    @Test
    void ignoresHoursSentByClient() {
        Attendance attendance = TestData.attendance(user, "2026-11-02", "出社", "09:00", "18:00", "01:00");
        attendance.setWorkHours(new BigDecimal("99"));
        attendance.setOvertimeHours(BigDecimal.ZERO);

        Attendance saved = attendanceService.updateAttendance(attendance);

        assertThat(saved.getWorkHours()).isEqualByComparingTo("8.00");
        assertThat(saved.getOvertimeHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void recalculatesWhenTimesChange() {
        Attendance attendance = TestData.attendance(user, "2026-11-02", "出社", "09:00", "18:00", "01:00");
        attendanceService.updateAttendance(attendance);

        attendance.setEndTime(java.time.LocalTime.of(21, 0));
        Attendance saved = attendanceService.updateAttendance(attendance);

        assertThat(saved.getWorkHours()).isEqualByComparingTo("11.00");
        assertThat(saved.getOvertimeHours()).isEqualByComparingTo("3.00");
    }

    @Test
    void missingEndTimeGivesZeroHours() {
        Attendance attendance = TestData.attendance(user, "2026-11-02", "出社", "09:00", null, "01:00");

        Attendance saved = attendanceService.updateAttendance(attendance);

        assertThat(saved.getWorkHours()).isEqualByComparingTo("0");
        assertThat(saved.getOvertimeHours()).isEqualByComparingTo("0");
    }

    // ---------- 月のロック ----------

    @Test
    void rejectsChangesToSubmittedMonth() {
        lockMonth(2026, 11, ApplicationStatus.SUBMITTED);
        Attendance attendance = TestData.attendance(user, "2026-11-02", "出社", "09:00", "18:00", "01:00");

        assertThatThrownBy(() -> attendanceService.updateAttendance(attendance))
                .isInstanceOf(IllegalStateException.class);
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void draftAndRejectedMonthsAreEditable() {
        lockMonth(2026, 11, ApplicationStatus.DRAFT);
        assertThat(attendanceService.isMonthLocked(user, 2026, 11)).isFalse();

        lockMonth(2026, 11, ApplicationStatus.REJECTED);
        assertThat(attendanceService.isMonthLocked(user, 2026, 11)).isFalse();

        lockMonth(2026, 11, ApplicationStatus.MANAGER_APPROVED);
        assertThat(attendanceService.isMonthLocked(user, 2026, 11)).isTrue();
    }

    // ---------- 月次統計（勤怠画面・ホーム・月報・Excel で共通） ----------

    @Test
    void monthlyStatisticsCountOnlyWorkedOfficeAndRemoteDays() {
        List<Attendance> attendances = List.of(
                TestData.attendanceWithHours(user, "2026-11-02", "出社", "8.00", "0.00"),
                TestData.attendanceWithHours(user, "2026-11-03", "在宅", "3.00", "3.00"),
                TestData.attendanceWithHours(user, "2026-11-04", "有給", "8.00", "0.00"),  // スキップ
                TestData.attendanceWithHours(user, "2026-11-05", "欠勤", "1.00", "0.00"),  // スキップ
                TestData.attendanceWithHours(user, "2026-11-06", "-", "1.00", "0.00"),     // スキップ
                TestData.attendanceWithHours(user, "2026-11-09", "出社", "0.00", "0.00"),  // 実働 0 は日数に含めない
                TestData.attendanceWithHours(user, "2026-10-30", "出社", "8.00", "0.00")); // 他の月

        AttendanceService.MonthlyStatistics statistics =
                attendanceService.calculateMonthlyStatistics(attendances, YearMonth.of(2026, 11));

        assertThat(statistics.workDays()).isEqualTo(2);
        assertThat(statistics.workHours()).isEqualByComparingTo("11.00");
        assertThat(statistics.overtimeHours()).isEqualByComparingTo("3.00");
    }

    // ---------- 起動時の再計算 ----------

    @Test
    void recalculationSkipsLockedMonths() {
        Attendance unlocked = TestData.attendance(user, "2026-11-13", "出社", "09:00", "18:00", "01:00");
        unlocked.setWorkHours(new BigDecimal("9.00"));
        unlocked.setOvertimeHours(new BigDecimal("1.00"));
        Attendance locked = TestData.attendance(user, "2026-10-05", "出社", "09:00", "18:00", "01:00");
        locked.setWorkHours(new BigDecimal("9.00"));
        locked.setOvertimeHours(new BigDecimal("1.00"));
        when(attendanceRepository.findAll()).thenReturn(List.of(unlocked, locked));
        lockMonth(2026, 10, ApplicationStatus.SUBMITTED);

        int changed = attendanceService.recalculateUnlockedAttendances();

        assertThat(changed).isEqualTo(1);
        assertThat(unlocked.getWorkHours()).isEqualByComparingTo("8.00");
        assertThat(locked.getWorkHours()).isEqualByComparingTo("9.00");
        assertThat(locked.getOvertimeHours()).isEqualByComparingTo("1.00");
    }

    @Test
    void recalculationDoesNotSaveUnchangedRecords() {
        Attendance correct = TestData.attendance(user, "2026-11-02", "出社", "09:00", "18:00", "01:00");
        correct.setWorkHours(new BigDecimal("8.00"));
        correct.setOvertimeHours(new BigDecimal("0.00"));
        when(attendanceRepository.findAll()).thenReturn(List.of(correct));

        assertThat(attendanceService.recalculateUnlockedAttendances()).isZero();
        verify(attendanceRepository, never()).save(any());
    }

    private void lockMonth(int year, int month, ApplicationStatus status) {
        MonthlyReport report = new MonthlyReport();
        report.setStatus(status);
        when(monthlyReportRepository.findByUserIdAndYearAndMonth(eq(user.getId()), eq(year), eq(month)))
                .thenReturn(Optional.of(report));
    }
}
