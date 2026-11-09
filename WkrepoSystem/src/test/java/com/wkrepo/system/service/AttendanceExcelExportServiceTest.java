package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Excel 出力：4 シートの構成と内容、対象ユーザー以外のデータを出さないこと、大量データ（SXSSF） */
class AttendanceExcelExportServiceTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    private final AttendanceExcelExportService service = new AttendanceExcelExportService(new AttendanceService());

    private final User yamada = user(1L, "EMP003", "山田太郎");
    private final User tanaka = user(2L, "EMP004", "田中花子");
    private final User other = user(9L, "EMP900", "他部署");

    @Test
    void exportsFourSheetsInOrder() throws Exception {
        try (Workbook workbook = export(List.of(yamada), List.of(), List.of(), List.of())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetName(0)).isEqualTo("月度集計");
            assertThat(workbook.getSheetName(1)).isEqualTo("出勤明細");
            assertThat(workbook.getSheetName(2)).isEqualTo("交通費明細");
            assertThat(workbook.getSheetName(3)).isEqualTo("諸経費明細");
        }
    }

    @Test
    void summaryHasOneRowPerUserWithNightHolidayAndApprovedExpenses() throws Exception {
        Attendance sunday = worked(yamada, "2026-11-01", "4.00", "4.00");
        sunday.setNightHours(new BigDecimal("1.00"));
        sunday.setHolidayHours(new BigDecimal("4.00"));
        List<Attendance> attendances = List.of(sunday, worked(yamada, "2026-11-02", "8.00", "0.00"));
        List<TravelExpense> travel = List.of(
                travel(yamada, "2026-11-02", "非課税交通費", "480", ApplicationStatus.APPROVED),
                travel(yamada, "2026-11-03", "旅費交通費", "9999", ApplicationStatus.PENDING)); // 未承認は集計外

        try (Workbook workbook = export(List.of(tanaka, yamada), attendances, travel, List.of())) {
            Sheet summary = workbook.getSheet("月度集計");
            assertThat(cellText(summary.getRow(2), 6)).isEqualTo("深夜労働（時間）");
            Row first = summary.getRow(3); // 社員番号順：EMP003 山田
            assertThat(cellText(first, 0)).isEqualTo("EMP003");
            assertThat(first.getCell(3).getNumericCellValue()).isEqualTo(2);
            assertThat(first.getCell(4).getNumericCellValue()).isEqualTo(12.0);
            assertThat(first.getCell(6).getNumericCellValue()).isEqualTo(1.0);
            assertThat(first.getCell(7).getNumericCellValue()).isEqualTo(4.0);
            assertThat(first.getCell(8).getNumericCellValue()).isEqualTo(480.0);
            assertThat(first.getCell(11).getNumericCellValue()).isEqualTo(480.0);
            assertThat(cellText(summary.getRow(4), 0)).isEqualTo("EMP004"); // 勤怠のない人も 1 行
        }
    }

    @Test
    void attendanceDetailListsOnlyDaysWithStatus() throws Exception {
        Attendance night = TestData.attendance(yamada, "2026-11-05", "出社", "22:00", "06:00", "01:00");
        night.setEndNextDay(true);
        night.setWorkHours(new BigDecimal("7.00"));
        night.setOvertimeHours(BigDecimal.ZERO);
        night.setNightHours(new BigDecimal("7.00"));
        night.setHolidayHours(BigDecimal.ZERO);
        night.setRemarks("夜間作業");
        Attendance empty = TestData.attendance(yamada, "2026-11-06", "-", null, null, null);
        Attendance leave = TestData.attendance(yamada, "2026-11-04", "有給", null, null, null);

        try (Workbook workbook = export(List.of(yamada), List.of(night, empty, leave), List.of(), List.of())) {
            Sheet detail = workbook.getSheet("出勤明細");
            assertThat(detail.getLastRowNum()).isEqualTo(4); // ヘッダー（3 行目）+ 2 日分（"-" は出さない）
            Row leaveRow = detail.getRow(3);
            assertThat(leaveRow.getCell(0).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(LocalDate.of(2026, 11, 4));
            assertThat(cellText(leaveRow, 5)).isEqualTo("有給");
            Row nightRow = detail.getRow(4);
            assertThat(cellText(nightRow, 1)).isEqualTo("木");
            assertThat(cellText(nightRow, 3)).isEqualTo("山田太郎");
            assertThat(cellText(nightRow, 6)).isEqualTo("22:00");
            assertThat(cellText(nightRow, 7)).isEqualTo("06:00");
            assertThat(cellText(nightRow, 8)).isEqualTo("翌日");
            assertThat(cellText(nightRow, 9)).isEqualTo("01:00");
            assertThat(nightRow.getCell(12).getNumericCellValue()).isEqualTo(7.0);
            assertThat(cellText(nightRow, 14)).isEqualTo("夜間作業");
        }
    }

    @Test
    void dataOfUsersOutsideTheTargetIsNotExported() throws Exception {
        List<Attendance> attendances = List.of(worked(other, "2026-11-02", "8.00", "0.00"));
        List<TravelExpense> travel = List.of(travel(other, "2026-11-02", "旅費交通費", "100", ApplicationStatus.APPROVED));
        List<GeneralExpense> general = List.of(general(other, "2026-11-02", "200"), general(yamada, "2026-11-03", "300"));

        try (Workbook workbook = export(List.of(yamada), attendances, travel, general)) {
            assertThat(workbook.getSheet("月度集計").getLastRowNum()).isEqualTo(3);
            assertThat(workbook.getSheet("出勤明細").getLastRowNum()).isEqualTo(2); // ヘッダーのみ
            assertThat(workbook.getSheet("交通費明細").getRow(3)).isNull();
            Sheet generalSheet = workbook.getSheet("諸経費明細");
            assertThat(cellText(generalSheet.getRow(3), 2)).isEqualTo("山田太郎");
            assertThat(generalSheet.getRow(4)).isNull(); // 他部署の 200 円は出さない
        }
    }

    @Test
    void largeExportIsStreamed() throws Exception {
        // ウィンドウ（200 行）を大きく超える件数：300 人 × 30 日 = 9,000 行
        List<User> users = new ArrayList<>();
        List<Attendance> attendances = new ArrayList<>();
        for (long id = 1; id <= 300; id++) {
            User user = user(id, String.format("EMP%04d", id), "社員" + id);
            users.add(user);
            for (int day = 1; day <= 30; day++) {
                attendances.add(worked(user, NOVEMBER.atDay(day).toString(), "8.00", "0.00"));
            }
        }

        try (Workbook workbook = export(users, attendances, List.of(), List.of())) {
            Sheet detail = workbook.getSheet("出勤明細");
            assertThat(detail.getLastRowNum()).isEqualTo(2 + 9000);
            assertThat(cellText(detail.getRow(3), 2)).isEqualTo("EMP0001");
            assertThat(cellText(detail.getRow(2 + 9000), 2)).isEqualTo("EMP0300");
            assertThat(workbook.getSheet("月度集計").getLastRowNum()).isEqualTo(2 + 300);
        }
    }

    private Workbook export(List<User> users, List<Attendance> attendances,
                            List<TravelExpense> travel, List<GeneralExpense> general) throws Exception {
        byte[] bytes = service.exportMonthlyAttendance(users, attendances, travel, general, NOVEMBER);
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    private static String cellText(Row row, int column) {
        return row.getCell(column).getStringCellValue();
    }

    private static User user(long id, String employeeId, String name) {
        User user = TestData.user(id, "GENERAL", "开发部");
        user.setEmployeeId(employeeId);
        user.setName(name);
        return user;
    }

    private static Attendance worked(User user, String date, String work, String overtime) {
        Attendance attendance = TestData.attendanceWithHours(user, date, "出社", work, overtime);
        attendance.setStartTime(LocalTime.of(9, 0));
        attendance.setEndTime(LocalTime.of(18, 0));
        return attendance;
    }

    private static TravelExpense travel(User user, String date, String type, String amount, ApplicationStatus status) {
        TravelExpense expense = new TravelExpense();
        expense.setUser(user);
        expense.setExpenseDate(LocalDate.parse(date));
        expense.setType(type);
        expense.setAmount(new BigDecimal(amount));
        expense.setStatus(status);
        return expense;
    }

    private static GeneralExpense general(User user, String date, String amount) {
        GeneralExpense expense = new GeneralExpense();
        expense.setUser(user);
        expense.setDate(LocalDate.parse(date));
        expense.setAmount(new BigDecimal(amount));
        expense.setStatus(ApplicationStatus.APPROVED);
        return expense;
    }
}
