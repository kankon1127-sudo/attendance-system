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
    private HolidayService holidayService = TestData.holidayService();

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
            // 説明,                           日付,        区分, 出勤,  退勤,  翌日,  休憩,  実働,  残業, 深夜, 休日, 保存される休憩
            "'平日 9-18 休1h',                2026-11-02, 出社, 09:00, 18:00, false, 01:00, 8.00, 0.00, 0.00, 0.00, 01:00",
            "'平日 9-21 休1h 有加班',          2026-11-02, 在宅, 09:00, 21:00, false, 01:00, 11.00, 3.00, 0.00, 0.00, 01:00",
            "'周日 9-19 休1h 全部算加班和休日', 2026-11-01, 出社, 09:00, 19:00, false, 01:00, 9.00, 9.00, 0.00, 9.00, 01:00",
            "'周六 9-18 算加班不算休日',        2026-11-07, 出社, 09:00, 18:00, false, 01:00, 8.00, 8.00, 0.00, 0.00, 01:00",
            "'文化の日 9-12 全部算加班',       2026-11-03, 出社, 09:00, 12:00, false, 00:00, 3.00, 3.00, 0.00, 0.00, 00:00",
            "'跨零点 22-06 休1h 全部深夜',     2026-11-05, 出社, 22:00, 06:00, true,  01:00, 7.00, 0.00, 7.00, 0.00, 01:00",
            "'18-24 深夜 2h',                 2026-11-05, 出社, 18:00, 00:00, true,  01:00, 5.00, 0.00, 2.00, 0.00, 01:00",
            "'早朝 4-13 深夜 1h',             2026-11-05, 出社, 04:00, 13:00, false, 01:00, 8.00, 0.00, 1.00, 0.00, 01:00",
            "'休息超过在岗时间',               2026-11-10, 出社, 09:00, 10:00, false, 02:00, 0.00, 0.00, 0.00, 0.00, 02:00",
            "'没有休息 8.5h → 自动补 60 分',   2026-11-02, 出社, 09:00, 17:30, false, ,      7.50, 0.00, 0.00, 0.00, 01:00",
            "'休息 45 分 9h → 补到 60 分',     2026-11-02, 出社, 09:00, 18:00, false, 00:45, 8.00, 0.00, 0.00, 0.00, 01:00",
            "'没有休息 6.5h → 自动补 45 分',   2026-11-02, 出社, 09:00, 15:30, false, 00:00, 5.75, 0.00, 0.00, 0.00, 00:45",
            "'刚好 6h 不需要休息',             2026-11-02, 出社, 09:00, 15:00, false, 00:00, 6.00, 0.00, 0.00, 0.00, 00:00",
            "'刚好 8h + 45 分 不需要补',       2026-11-02, 出社, 09:00, 17:45, false, 00:45, 8.00, 0.00, 0.00, 0.00, 00:45",
            "'有給 即使有时间也为 0',          2026-11-04, 有給, 09:00, 18:00, false, 01:00, 0.00, 0.00, 0.00, 0.00, 01:00",
            "'欠勤 为 0',                      2026-11-04, 欠勤, 09:00, 18:00, false, 01:00, 0.00, 0.00, 0.00, 0.00, 01:00",
            "'区分为 - 为 0',                  2026-11-04, -,    09:00, 18:00, false, 01:00, 0.00, 0.00, 0.00, 0.00, 01:00",
    })
    void calculatesWorkOvertimeNightAndHolidayHours(String description, String date, String status,
                                                    String start, String end, boolean endNextDay, String breakTime,
                                                    String expectedWork, String expectedOvertime,
                                                    String expectedNight, String expectedHoliday,
                                                    String expectedBreak) {
        Attendance attendance = TestData.attendance(user, date, status, start, end, breakTime);
        attendance.setEndNextDay(endNextDay);

        Attendance saved = attendanceService.updateAttendance(attendance);

        assertThat(saved.getWorkHours()).isEqualByComparingTo(expectedWork);
        assertThat(saved.getOvertimeHours()).isEqualByComparingTo(expectedOvertime);
        assertThat(saved.getNightHours()).isEqualByComparingTo(expectedNight);
        assertThat(saved.getHolidayHours()).isEqualByComparingTo(expectedHoliday);
        assertThat(saved.getBreakTime()).isEqualTo(java.time.LocalTime.parse(expectedBreak));
    }

    // ---------- 出勤・退勤の前後関係 ----------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "'退勤が出勤より前（翌日なし）', 18:00, 09:00, false, attendance.error.endBeforeStart",
            "'退勤と出勤が同じ（翌日なし）', 09:00, 09:00, false, attendance.error.endBeforeStart",
            "'翌日なのに退勤が出勤より後（24h 超）', 09:00, 10:00, true, attendance.error.shiftTooLong",
    })
    void rejectsInvalidTimeOrder(String description, String start, String end, boolean endNextDay, String messageKey) {
        Attendance attendance = TestData.attendance(user, "2026-11-02", "出社", start, end, "01:00");
        attendance.setEndNextDay(endNextDay);

        assertThatThrownBy(() -> attendanceService.updateAttendance(attendance)).hasMessage(messageKey);
        verify(attendanceRepository, never()).save(any());
    }

    // ---------- #39 勤務区分：半休・特別休暇・振休・代休 ----------

    @Test
    void halfDayOffCountsAsWorkDayWithHours() {
        Attendance morningOff = TestData.attendance(user, "2026-11-02", "午前休", "13:00", "18:00", "00:00");

        Attendance saved = attendanceService.updateAttendance(morningOff);

        assertThat(saved.getWorkHours()).isEqualByComparingTo("5.00");
        assertThat(attendanceService.calculateMonthlyStatistics(List.of(saved), YearMonth.of(2026, 11)).workDays())
                .isEqualTo(1);
    }

    @Test
    void leaveStatusesHaveNoHoursEvenWithTimes() {
        Attendance special = TestData.attendance(user, "2026-11-02", "特別休暇", "09:00", "18:00", "01:00");
        special.setRemarks("慶弔（結婚）");

        Attendance saved = attendanceService.updateAttendance(special);

        assertThat(saved.getWorkHours()).isEqualByComparingTo("0");
        assertThat(attendanceService.calculateMonthlyStatistics(List.of(saved), YearMonth.of(2026, 11)).workDays())
                .isZero();
    }

    @Test
    void specialLeaveRequiresReason() {
        Attendance special = TestData.attendance(user, "2026-11-02", "特別休暇", null, null, null);

        assertThatThrownBy(() -> attendanceService.updateAttendance(special))
                .hasMessage("attendance.error.specialLeaveReasonRequired");
    }

    @Test
    void substituteHolidayNeedsWorkedRestDay() {
        Attendance holidayWork = sundayWork("2026-11-08");

        Attendance noDate = TestData.attendance(user, "2026-11-10", "振休", null, null, null);
        assertThatThrownBy(() -> attendanceService.updateAttendance(noDate))
                .hasMessage("attendance.error.substituteDateRequired");

        Attendance weekday = TestData.attendance(user, "2026-11-10", "振休", null, null, null);
        weekday.setSubstituteDate(java.time.LocalDate.of(2026, 11, 9)); // 月曜日（休日ではない）
        assertThatThrownBy(() -> attendanceService.updateAttendance(weekday))
                .hasMessage("attendance.error.substituteNotHolidayWork");

        Attendance ok = TestData.attendance(user, "2026-11-10", "振休", null, null, null);
        ok.setSubstituteDate(holidayWork.getDate());
        assertThat(attendanceService.updateAttendance(ok).getSubstituteDate()).isEqualTo(holidayWork.getDate());
    }

    @Test
    void compensatoryLeaveMustBeAfterHolidayWorkWithinPeriod() {
        sundayWork("2026-11-08");

        Attendance before = TestData.attendance(user, "2026-11-06", "代休", null, null, null);
        before.setSubstituteDate(java.time.LocalDate.of(2026, 11, 8));
        assertThatThrownBy(() -> attendanceService.updateAttendance(before))
                .hasMessage("attendance.error.compensatoryOutOfRange");

        Attendance tooLate = TestData.attendance(user, "2027-01-15", "代休", null, null, null);
        tooLate.setSubstituteDate(java.time.LocalDate.of(2026, 11, 8));
        assertThatThrownBy(() -> attendanceService.updateAttendance(tooLate))
                .hasMessage("attendance.error.compensatoryOutOfRange");
    }

    @Test
    void holidayWorkCanBeUsedOnlyOnce() {
        sundayWork("2026-11-08");
        Attendance used = TestData.attendance(user, "2026-11-10", "代休", null, null, null);
        used.setId(50L);
        used.setSubstituteDate(java.time.LocalDate.of(2026, 11, 8));
        when(attendanceRepository.findByUserAndSubstituteDate(user, java.time.LocalDate.of(2026, 11, 8)))
                .thenReturn(List.of(used));

        Attendance second = TestData.attendance(user, "2026-11-11", "代休", null, null, null);
        second.setSubstituteDate(java.time.LocalDate.of(2026, 11, 8));
        assertThatThrownBy(() -> attendanceService.updateAttendance(second))
                .hasMessage("attendance.error.substituteAlreadyUsed");
    }

    @Test
    void substitutedSundayIsCalculatedAsWorkday() {
        when(attendanceRepository.existsByUserAndSubstituteDateAndAgreement36Status(
                user, java.time.LocalDate.of(2026, 11, 8), "振休")).thenReturn(true);
        Attendance sunday = TestData.attendance(user, "2026-11-08", "出社", "09:00", "19:00", "01:00");

        Attendance saved = attendanceService.updateAttendance(sunday);

        assertThat(saved.getWorkHours()).isEqualByComparingTo("9.00");
        assertThat(saved.getOvertimeHours()).isEqualByComparingTo("1.00"); // 平日扱い：8 時間超のみ
        assertThat(saved.getHolidayHours()).isEqualByComparingTo("0");     // 休日労働にしない
    }

    @Test
    void savingSubstituteHolidayRecalculatesTheHolidayWork() {
        Attendance holidayWork = sundayWork("2026-11-08");
        when(attendanceRepository.existsByUserAndSubstituteDateAndAgreement36Status(
                user, holidayWork.getDate(), "振休")).thenReturn(true);
        Attendance substitute = TestData.attendance(user, "2026-11-10", "振休", null, null, null);
        substitute.setSubstituteDate(holidayWork.getDate());

        attendanceService.updateAttendance(substitute);

        verify(attendanceRepository).save(holidayWork);
        assertThat(holidayWork.getHolidayHours()).isEqualByComparingTo("0");
        assertThat(holidayWork.getOvertimeHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void halfDayOffNeedsApprovedHalfDayLeaveToSubmitMonth() {
        when(attendanceRepository.findByUserAndDateBetweenOrderByDateAsc(eq(user), any(), any()))
                .thenReturn(List.of(TestData.attendance(user, "2026-11-02", "午前休", "13:00", "18:00", null)));
        when(paidLeaveService.isHalfDayLeaveApproved(user, java.time.LocalDate.of(2026, 11, 2), "午前")).thenReturn(false);

        assertThatThrownBy(() -> attendanceService.validatePaidLeaveApprovedForMonthlySubmit(user, 2026, 11))
                .hasMessage("monthly.error.paidLeaveNotApproved");

        when(paidLeaveService.isHalfDayLeaveApproved(user, java.time.LocalDate.of(2026, 11, 2), "午前")).thenReturn(true);
        attendanceService.validatePaidLeaveApprovedForMonthlySubmit(user, 2026, 11);
    }

    /** 日曜日の休日出勤（9-18、実働 8 時間）を登録済みにする */
    private Attendance sundayWork(String date) {
        Attendance work = TestData.attendance(user, date, "出社", "09:00", "18:00", "01:00");
        work.setWorkHours(new BigDecimal("8.00"));
        work.setOvertimeHours(new BigDecimal("8.00"));
        work.setHolidayHours(new BigDecimal("8.00"));
        when(attendanceRepository.findByUserAndDate(user, java.time.LocalDate.parse(date))).thenReturn(Optional.of(work));
        return work;
    }

    @Test
    void timeOrderIsNotCheckedForLeaveOrMissingTimes() {
        Attendance leave = TestData.attendance(user, "2026-11-04", "有給", "18:00", "09:00", null);
        Attendance noEnd = TestData.attendance(user, "2026-11-05", "出社", "09:00", null, null);

        assertThat(attendanceService.updateAttendance(leave).getWorkHours()).isEqualByComparingTo("0");
        assertThat(attendanceService.updateAttendance(noEnd).getWorkHours()).isEqualByComparingTo("0");
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
        Attendance sunday = TestData.attendanceWithHours(user, "2026-11-01", "出社", "4.00", "4.00");
        sunday.setHolidayHours(new BigDecimal("4.00"));
        sunday.setNightHours(new BigDecimal("1.50"));
        List<Attendance> attendances = List.of(
                sunday,
                TestData.attendanceWithHours(user, "2026-11-02", "出社", "8.00", "0.00"),
                TestData.attendanceWithHours(user, "2026-11-03", "在宅", "3.00", "3.00"),
                TestData.attendanceWithHours(user, "2026-11-04", "有給", "8.00", "0.00"),  // スキップ
                TestData.attendanceWithHours(user, "2026-11-05", "欠勤", "1.00", "0.00"),  // スキップ
                TestData.attendanceWithHours(user, "2026-11-06", "-", "1.00", "0.00"),     // スキップ
                TestData.attendanceWithHours(user, "2026-11-09", "出社", "0.00", "0.00"),  // 実働 0 は日数に含めない
                TestData.attendanceWithHours(user, "2026-10-30", "出社", "8.00", "0.00")); // 他の月

        AttendanceService.MonthlyStatistics statistics =
                attendanceService.calculateMonthlyStatistics(attendances, YearMonth.of(2026, 11));

        assertThat(statistics.workDays()).isEqualTo(3);
        assertThat(statistics.workHours()).isEqualByComparingTo("15.00");
        assertThat(statistics.overtimeHours()).isEqualByComparingTo("7.00");
        assertThat(statistics.nightHours()).isEqualByComparingTo("1.50");
        assertThat(statistics.holidayHours()).isEqualByComparingTo("4.00");
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

        // ロック済みも新しい項目（翌日・深夜・休日）を補うため保存されるが、実働・残業は変わらない
        assertThat(changed).isEqualTo(2);
        assertThat(unlocked.getWorkHours()).isEqualByComparingTo("8.00");
        assertThat(locked.getWorkHours()).isEqualByComparingTo("9.00");
        assertThat(locked.getOvertimeHours()).isEqualByComparingTo("1.00");
        assertThat(locked.getNightHours()).isEqualByComparingTo("0.00");
        assertThat(locked.getEndNextDay()).isFalse();
    }

    @Test
    void lockedMonthKeepsBreakEvenIfBelowLegalMinimum() {
        // 承認済みの数値は変えない：休憩 0 分・実働 9 時間のまま
        Attendance locked = TestData.attendance(user, "2026-10-05", "出社", "09:00", "18:00", "00:00");
        locked.setWorkHours(new BigDecimal("9.00"));
        locked.setOvertimeHours(new BigDecimal("1.00"));
        when(attendanceRepository.findAll()).thenReturn(List.of(locked));
        lockMonth(2026, 10, ApplicationStatus.SUBMITTED);

        attendanceService.recalculateUnlockedAttendances();

        assertThat(locked.getBreakTime()).isEqualTo(java.time.LocalTime.of(0, 0));
        assertThat(locked.getWorkHours()).isEqualByComparingTo("9.00");
    }

    @Test
    void legacyCrossMidnightRecordIsMarkedAsNextDay() {
        // 「翌日」列ができる前のデータ：退勤が出勤より前なら日をまたぐ勤務だった
        Attendance legacy = TestData.attendance(user, "2026-11-05", "出社", "22:00", "06:00", "01:00");
        legacy.setWorkHours(new BigDecimal("7.00"));
        legacy.setOvertimeHours(BigDecimal.ZERO);
        when(attendanceRepository.findAll()).thenReturn(List.of(legacy));

        attendanceService.recalculateUnlockedAttendances();

        assertThat(legacy.getEndNextDay()).isTrue();
        assertThat(legacy.getWorkHours()).isEqualByComparingTo("7.00");
        assertThat(legacy.getNightHours()).isEqualByComparingTo("7.00");
    }

    @Test
    void recalculationDoesNotSaveUnchangedRecords() {
        Attendance correct = TestData.attendance(user, "2026-11-02", "出社", "09:00", "18:00", "01:00");
        correct.setWorkHours(new BigDecimal("8.00"));
        correct.setOvertimeHours(new BigDecimal("0.00"));
        correct.setEndNextDay(false);
        correct.setNightHours(new BigDecimal("0.00"));
        correct.setHolidayHours(new BigDecimal("0.00"));
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
