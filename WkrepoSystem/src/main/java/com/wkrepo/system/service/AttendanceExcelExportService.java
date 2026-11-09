package com.wkrepo.system.service;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 月度勤怠・経費の Excel 出力（4 シート）。
 * <ol>
 *   <li>月度集計：1 人 1 行（勤怠の合計は AttendanceService.calculateMonthlyStatistics、経費は承認済の合計）</li>
 *   <li>出勤明細：1 人 1 日 1 行（区分が入力されている日）</li>
 *   <li>交通費明細 / 諸経費明細：全ステータスの経費を 1 件ずつ出力し、末尾行は承認済の合計</li>
 * </ol>
 * 総務・管理者の全員一括出力でもメモリを使いすぎないよう、SXSSF（ストリーミング）で書き出す。
 * メモリ上に保持するのは各シート直近 {@value #ROW_ACCESS_WINDOW} 行だけで、それ以前の行は一時ファイルに書き出される。
 * 申請者の情報は対象ユーザー一覧から取り、勤怠・経費の user（LAZY）は ID しか参照しない（N+1 クエリを避ける）。
 */
@Service
public class AttendanceExcelExportService {
    static final int ROW_ACCESS_WINDOW = 200;

    static final String SUMMARY_SHEET = "月度集計";
    static final String ATTENDANCE_SHEET = "出勤明細";
    static final String TRAVEL_SHEET = "交通費明細";
    static final String GENERAL_SHEET = "諸経費明細";

    private static final List<Column> SUMMARY_COLUMNS = List.of(
            new Column("社員番号", 12), new Column("氏名", 16), new Column("部署", 14),
            new Column("出勤日数", 10), new Column("実働時間（時間）", 16), new Column("残業時間（時間）", 16),
            new Column("深夜労働（時間）", 16), new Column("休日労働（時間）", 16),
            new Column("非課税交通費", 14), new Column("旅費交通費", 14), new Column("諸経費", 12),
            new Column("経費合計", 12));
    private static final List<Column> ATTENDANCE_COLUMNS = List.of(
            new Column("日付", 12), new Column("曜日", 6), new Column("社員番号", 12), new Column("氏名", 16),
            new Column("部署", 14), new Column("区分", 8), new Column("出勤", 8), new Column("退勤", 8),
            new Column("翌日", 6), new Column("休憩", 8), new Column("実働（時間）", 12), new Column("残業（時間）", 12),
            new Column("深夜（時間）", 12), new Column("休日（時間）", 12), new Column("備考", 30));
    private static final List<Column> TRAVEL_COLUMNS = List.of(
            new Column("日付", 12), new Column("社員番号", 12), new Column("氏名", 16), new Column("部署", 14),
            new Column("区分", 14), new Column("交通機関", 12), new Column("区間", 24), new Column("摘要", 24),
            new Column("金額", 12), new Column("状態", 10));
    private static final List<Column> GENERAL_COLUMNS = List.of(
            new Column("日付", 12), new Column("社員番号", 12), new Column("氏名", 16), new Column("部署", 14),
            new Column("種類", 12), new Column("摘要", 24), new Column("金額", 12), new Column("備考", 30),
            new Column("状態", 10));
    private static final String NON_TAXABLE_TRAVEL = "非課税交通費";
    private static final String APPROVED_TOTAL_LABEL = "承認済合計";
    private static final int HEADER_ROW = 2;

    private final AttendanceService attendanceService;

    public AttendanceExcelExportService(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    /** テスト・小規模用：ブックをバイト配列で返す */
    public byte[] exportMonthlyAttendance(List<User> users, List<Attendance> attendances,
                                          List<TravelExpense> travelExpenses, List<GeneralExpense> generalExpenses,
                                          YearMonth yearMonth) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeMonthlyAttendance(users, attendances, travelExpenses, generalExpenses, yearMonth, output);
        return output.toByteArray();
    }

    /**
     * ブックを output に書き出す（output は閉じない）。
     *
     * @param users 出力対象のユーザー（呼び出し側で権限により絞り込み済み）。このユーザーのデータだけを出力する
     */
    public void writeMonthlyAttendance(List<User> users, List<Attendance> attendances,
                                       List<TravelExpense> travelExpenses, List<GeneralExpense> generalExpenses,
                                       YearMonth yearMonth, OutputStream output) throws IOException {
        Map<Long, User> usersById = users.stream()
                .filter(user -> user.getId() != null)
                .collect(Collectors.toMap(User::getId, Function.identity(), (first, second) -> first));
        List<User> orderedUsers = usersById.values().stream()
                .sorted(Comparator.comparing(AttendanceExcelExportService::employeeIdOf))
                .toList();

        Map<Long, List<Attendance>> attendanceByUser = new HashMap<>();
        for (Attendance attendance : attendances) {
            Long userId = userIdOf(attendance.getUser());
            if (usersById.containsKey(userId)) {
                attendanceByUser.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(attendance);
            }
        }
        List<TravelExpense> visibleTravelExpenses = travelExpenses.stream()
                .filter(expense -> usersById.containsKey(userIdOf(expense.getUser())))
                .sorted(Comparator.comparing((TravelExpense expense) -> employeeIdOf(usersById.get(userIdOf(expense.getUser()))))
                        .thenComparing(TravelExpense::getExpenseDate, Comparator.nullsLast(LocalDate::compareTo)))
                .toList();
        List<GeneralExpense> visibleGeneralExpenses = generalExpenses.stream()
                .filter(expense -> usersById.containsKey(userIdOf(expense.getUser())))
                .sorted(Comparator.comparing((GeneralExpense expense) -> employeeIdOf(usersById.get(userIdOf(expense.getUser()))))
                        .thenComparing(GeneralExpense::getDate, Comparator.nullsLast(LocalDate::compareTo)))
                .toList();

        SXSSFWorkbook workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        workbook.setCompressTempFiles(true);
        try {
            Styles styles = new Styles(workbook);
            writeSummarySheet(workbook, styles, orderedUsers, attendanceByUser,
                    visibleTravelExpenses, visibleGeneralExpenses, yearMonth);
            writeAttendanceSheet(workbook, styles, orderedUsers, attendanceByUser, yearMonth);
            writeTravelExpenseSheet(workbook, styles, usersById, visibleTravelExpenses, yearMonth);
            writeGeneralExpenseSheet(workbook, styles, usersById, visibleGeneralExpenses, yearMonth);
            workbook.write(output);
        } finally {
            workbook.close();
            workbook.dispose(); // 一時ファイルを削除する
        }
    }

    private void writeSummarySheet(Workbook workbook, Styles styles, List<User> users,
                                   Map<Long, List<Attendance>> attendanceByUser,
                                   List<TravelExpense> travelExpenses, List<GeneralExpense> generalExpenses,
                                   YearMonth yearMonth) {
        Map<Long, ExpenseTotals> expenseByUser = new HashMap<>();
        for (TravelExpense expense : travelExpenses) {
            if (isApproved(expense.getStatus())) {
                expenseByUser.computeIfAbsent(userIdOf(expense.getUser()), ignored -> new ExpenseTotals())
                        .addTravel(expense.getType(), expense.getAmount());
            }
        }
        for (GeneralExpense expense : generalExpenses) {
            if (isApproved(expense.getStatus())) {
                expenseByUser.computeIfAbsent(userIdOf(expense.getUser()), ignored -> new ExpenseTotals())
                        .addGeneral(expense.getAmount());
            }
        }

        Sheet sheet = workbook.createSheet(SUMMARY_SHEET);
        writeTitle(sheet, yearMonth, "勤怠・経費集計（経費は承認済のみ）");
        writeHeader(sheet, styles, SUMMARY_COLUMNS);

        int rowNumber = HEADER_ROW + 1;
        for (User user : users) {
            AttendanceService.MonthlyStatistics totals = attendanceService.calculateMonthlyStatistics(
                    attendanceByUser.getOrDefault(user.getId(), List.of()), yearMonth);
            ExpenseTotals expenses = expenseByUser.getOrDefault(user.getId(), new ExpenseTotals());
            Row row = sheet.createRow(rowNumber++);
            writeUserColumns(row, 0, user);
            row.createCell(3).setCellValue(totals.workDays());
            setNumber(row, 4, totals.workHours(), styles.hours);
            setNumber(row, 5, totals.overtimeHours(), styles.hours);
            setNumber(row, 6, totals.nightHours(), styles.hours);
            setNumber(row, 7, totals.holidayHours(), styles.hours);
            setNumber(row, 8, expenses.nonTaxableTravel, styles.amount);
            setNumber(row, 9, expenses.travel, styles.amount);
            setNumber(row, 10, expenses.general, styles.amount);
            setNumber(row, 11, expenses.total(), styles.amount);
        }
        finishSheet(sheet, SUMMARY_COLUMNS.size(), rowNumber);
    }

    /** 出勤明細：区分が入力されている日を 1 人 1 日 1 行で出力（社員番号 → 日付の順） */
    private void writeAttendanceSheet(Workbook workbook, Styles styles, List<User> users,
                                      Map<Long, List<Attendance>> attendanceByUser, YearMonth yearMonth) {
        Sheet sheet = workbook.createSheet(ATTENDANCE_SHEET);
        writeTitle(sheet, yearMonth, "出勤明細");
        writeHeader(sheet, styles, ATTENDANCE_COLUMNS);

        int rowNumber = HEADER_ROW + 1;
        for (User user : users) {
            List<Attendance> records = attendanceByUser.getOrDefault(user.getId(), List.of()).stream()
                    .filter(attendance -> attendance.getDate() != null
                            && YearMonth.from(attendance.getDate()).equals(yearMonth)
                            && hasStatus(attendance.getAgreement36Status()))
                    .sorted(Comparator.comparing(Attendance::getDate))
                    .toList();
            for (Attendance attendance : records) {
                Row row = sheet.createRow(rowNumber++);
                setDate(row, 0, attendance.getDate(), styles.date);
                row.createCell(1).setCellValue(
                        attendance.getDate().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.JAPAN));
                writeUserColumns(row, 2, user);
                row.createCell(5).setCellValue(textOf(attendance.getAgreement36Status()));
                row.createCell(6).setCellValue(timeOf(attendance.getStartTime()));
                row.createCell(7).setCellValue(timeOf(attendance.getEndTime()));
                row.createCell(8).setCellValue(attendance.isEndNextDay() && attendance.getEndTime() != null ? "翌日" : "");
                row.createCell(9).setCellValue(timeOf(attendance.getBreakTime()));
                setNumber(row, 10, valueOrZero(attendance.getWorkHours()), styles.hours);
                setNumber(row, 11, valueOrZero(attendance.getOvertimeHours()), styles.hours);
                setNumber(row, 12, valueOrZero(attendance.getNightHours()), styles.hours);
                setNumber(row, 13, valueOrZero(attendance.getHolidayHours()), styles.hours);
                row.createCell(14).setCellValue(textOf(attendance.getRemarks()));
            }
        }
        finishSheet(sheet, ATTENDANCE_COLUMNS.size(), rowNumber);
    }

    private void writeTravelExpenseSheet(Workbook workbook, Styles styles, Map<Long, User> usersById,
                                         List<TravelExpense> expenses, YearMonth yearMonth) {
        Sheet sheet = workbook.createSheet(TRAVEL_SHEET);
        writeTitle(sheet, yearMonth, "交通費明細");
        writeHeader(sheet, styles, TRAVEL_COLUMNS);

        int rowNumber = HEADER_ROW + 1;
        BigDecimal approvedTotal = BigDecimal.ZERO;
        for (TravelExpense expense : expenses) {
            Row row = sheet.createRow(rowNumber++);
            setDate(row, 0, expense.getExpenseDate(), styles.date);
            writeUserColumns(row, 1, usersById.get(userIdOf(expense.getUser())));
            row.createCell(4).setCellValue(textOf(expense.getType()));
            row.createCell(5).setCellValue(textOf(expense.getTransportation()));
            row.createCell(6).setCellValue(textOf(expense.getRoute()));
            row.createCell(7).setCellValue(textOf(expense.getSummary()));
            setNumber(row, 8, valueOrZero(expense.getAmount()), styles.amount);
            row.createCell(9).setCellValue(statusOf(expense.getStatus()));
            if (isApproved(expense.getStatus())) {
                approvedTotal = approvedTotal.add(valueOrZero(expense.getAmount()));
            }
        }
        int dataEnd = rowNumber;
        writeApprovedTotal(sheet, styles, rowNumber + 1, 7, approvedTotal);
        finishSheet(sheet, TRAVEL_COLUMNS.size(), dataEnd);
    }

    private void writeGeneralExpenseSheet(Workbook workbook, Styles styles, Map<Long, User> usersById,
                                          List<GeneralExpense> expenses, YearMonth yearMonth) {
        Sheet sheet = workbook.createSheet(GENERAL_SHEET);
        writeTitle(sheet, yearMonth, "諸経費明細");
        writeHeader(sheet, styles, GENERAL_COLUMNS);

        int rowNumber = HEADER_ROW + 1;
        BigDecimal approvedTotal = BigDecimal.ZERO;
        for (GeneralExpense expense : expenses) {
            Row row = sheet.createRow(rowNumber++);
            setDate(row, 0, expense.getDate(), styles.date);
            writeUserColumns(row, 1, usersById.get(userIdOf(expense.getUser())));
            row.createCell(4).setCellValue(textOf(expense.getExpenseType()));
            row.createCell(5).setCellValue(textOf(expense.getSummary()));
            setNumber(row, 6, valueOrZero(expense.getAmount()), styles.amount);
            row.createCell(7).setCellValue(textOf(expense.getRemarks()));
            row.createCell(8).setCellValue(statusOf(expense.getStatus()));
            if (isApproved(expense.getStatus())) {
                approvedTotal = approvedTotal.add(valueOrZero(expense.getAmount()));
            }
        }
        int dataEnd = rowNumber;
        writeApprovedTotal(sheet, styles, rowNumber + 1, 5, approvedTotal);
        finishSheet(sheet, GENERAL_COLUMNS.size(), dataEnd);
    }

    private void writeTitle(Sheet sheet, YearMonth yearMonth, String title) {
        sheet.createRow(0).createCell(0)
                .setCellValue(yearMonth.getYear() + "年" + yearMonth.getMonthValue() + "月 " + title);
    }

    /** ヘッダー行と列幅（SXSSF では autoSizeColumn が使えないため、列ごとに固定幅を指定する） */
    private void writeHeader(Sheet sheet, Styles styles, List<Column> columns) {
        Row headerRow = sheet.createRow(HEADER_ROW);
        for (int index = 0; index < columns.size(); index++) {
            Cell cell = headerRow.createCell(index);
            cell.setCellValue(columns.get(index).title());
            cell.setCellStyle(styles.header);
            sheet.setColumnWidth(index, columns.get(index).width() * 256);
        }
    }

    private void writeUserColumns(Row row, int firstColumn, User user) {
        row.createCell(firstColumn).setCellValue(user == null ? "" : textOf(user.getEmployeeId()));
        row.createCell(firstColumn + 1).setCellValue(user == null ? "" : textOf(user.getName()));
        row.createCell(firstColumn + 2).setCellValue(user == null ? "" : textOf(user.getDepartment()));
    }

    private void writeApprovedTotal(Sheet sheet, Styles styles, int rowNumber, int labelColumn, BigDecimal total) {
        Row row = sheet.createRow(rowNumber);
        Cell label = row.createCell(labelColumn);
        label.setCellValue(APPROVED_TOTAL_LABEL);
        label.setCellStyle(styles.bold);
        setNumber(row, labelColumn + 1, total, styles.boldAmount);
    }

    /** ヘッダー行の固定と、フィルター範囲（データ行のみ。末尾の合計行は含まない） */
    private void finishSheet(Sheet sheet, int columnCount, int dataEndExclusive) {
        sheet.createFreezePane(0, HEADER_ROW + 1);
        sheet.setAutoFilter(new CellRangeAddress(
                HEADER_ROW, Math.max(HEADER_ROW, dataEndExclusive - 1), 0, columnCount - 1));
    }

    private void setNumber(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(valueOrZero(value).doubleValue());
        cell.setCellStyle(style);
    }

    private void setDate(Row row, int column, LocalDate date, CellStyle style) {
        Cell cell = row.createCell(column);
        if (date != null) {
            cell.setCellValue(date);
            cell.setCellStyle(style);
        }
    }

    private static boolean hasStatus(String status) {
        return status != null && !status.isBlank() && !"-".equals(status.trim());
    }

    private static boolean isApproved(ApplicationStatus status) {
        return status == ApplicationStatus.APPROVED;
    }

    private static String statusOf(ApplicationStatus status) {
        return status == null ? "" : status.getDisplayName();
    }

    private static Long userIdOf(User user) {
        return user == null ? null : user.getId();
    }

    private static String employeeIdOf(User user) {
        return user == null || user.getEmployeeId() == null ? "" : user.getEmployeeId();
    }

    private static String textOf(String value) {
        return value == null ? "" : value;
    }

    private static String timeOf(LocalTime time) {
        return time == null ? "" : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record Column(String title, int width) {
    }

    private static final class Styles {
        final CellStyle header;
        final CellStyle hours;
        final CellStyle amount;
        final CellStyle date;
        final CellStyle bold;
        final CellStyle boldAmount;

        Styles(Workbook workbook) {
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            hours = workbook.createCellStyle();
            hours.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
            amount = workbook.createCellStyle();
            amount.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
            date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy/mm/dd"));

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            bold = workbook.createCellStyle();
            bold.setFont(boldFont);
            boldAmount = workbook.createCellStyle();
            boldAmount.setFont(boldFont);
            boldAmount.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
        }
    }

    private static final class ExpenseTotals {
        BigDecimal nonTaxableTravel = BigDecimal.ZERO;
        BigDecimal travel = BigDecimal.ZERO;
        BigDecimal general = BigDecimal.ZERO;

        void addTravel(String type, BigDecimal amount) {
            if (NON_TAXABLE_TRAVEL.equals(type)) {
                nonTaxableTravel = nonTaxableTravel.add(valueOrZero(amount));
            } else {
                travel = travel.add(valueOrZero(amount));
            }
        }

        void addGeneral(BigDecimal amount) {
            general = general.add(valueOrZero(amount));
        }

        BigDecimal total() {
            return nonTaxableTravel.add(travel).add(general);
        }
    }
}
