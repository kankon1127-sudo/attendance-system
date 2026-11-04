package com.wkrepo.system.service;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 月度勤怠・経費集計の Excel 出力。
 * - 勤怠集計：1 人 1 行（勤怠の合計は AttendanceService.calculateMonthlyStatistics、経費は承認済の合計）
 * - 交通費明細 / 諸経費明細：全ステータスの経費を 1 件ずつ出力し、末尾行は承認済の合計
 */
@Service
public class AttendanceExcelExportService {
    private static final List<String> SUMMARY_HEADERS = List.of(
            "社員番号", "氏名", "部署", "出勤日数", "実働時間（時間）", "残業時間（時間）",
            "非課税交通費", "旅費交通費", "諸経費", "経費合計");
    private static final List<String> TRAVEL_HEADERS = List.of(
            "日付", "社員番号", "氏名", "部署", "区分", "交通機関", "区間", "摘要", "金額", "状態");
    private static final List<String> GENERAL_HEADERS = List.of(
            "日付", "社員番号", "氏名", "部署", "種類", "摘要", "金額", "備考", "状態");
    private static final String NON_TAXABLE_TRAVEL = "非課税交通費";
    private static final String APPROVED_TOTAL_LABEL = "承認済合計";
    private static final int HEADER_ROW = 2;

    private final AttendanceService attendanceService;

    public AttendanceExcelExportService(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    public byte[] exportMonthlyAttendance(List<User> users, List<Attendance> attendances,
                                          List<TravelExpense> travelExpenses, List<GeneralExpense> generalExpenses,
                                          YearMonth yearMonth) throws IOException {
        // users（呼び出し側で権限により絞り込み済み）のデータのみ出力する
        Set<Long> visibleUserIds = users.stream().map(User::getId).collect(Collectors.toSet());
        Map<Long, List<Attendance>> attendanceByUser = new HashMap<>();
        for (Attendance attendance : attendances) {
            attendanceByUser.computeIfAbsent(attendance.getUser().getId(), ignored -> new ArrayList<>()).add(attendance);
        }
        List<TravelExpense> visibleTravelExpenses = travelExpenses.stream()
                .filter(expense -> expense.getUser() != null && visibleUserIds.contains(expense.getUser().getId()))
                .sorted(Comparator.comparing((TravelExpense expense) -> employeeIdOf(expense.getUser()))
                        .thenComparing(TravelExpense::getExpenseDate, Comparator.nullsLast(LocalDate::compareTo)))
                .toList();
        List<GeneralExpense> visibleGeneralExpenses = generalExpenses.stream()
                .filter(expense -> expense.getUser() != null && visibleUserIds.contains(expense.getUser().getId()))
                .sorted(Comparator.comparing((GeneralExpense expense) -> employeeIdOf(expense.getUser()))
                        .thenComparing(GeneralExpense::getDate, Comparator.nullsLast(LocalDate::compareTo)))
                .toList();

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            writeSummarySheet(workbook, styles, users, attendanceByUser,
                    visibleTravelExpenses, visibleGeneralExpenses, yearMonth);
            writeTravelExpenseSheet(workbook, styles, visibleTravelExpenses, yearMonth);
            writeGeneralExpenseSheet(workbook, styles, visibleGeneralExpenses, yearMonth);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private void writeSummarySheet(Workbook workbook, Styles styles, List<User> users,
                                   Map<Long, List<Attendance>> attendanceByUser,
                                   List<TravelExpense> travelExpenses, List<GeneralExpense> generalExpenses,
                                   YearMonth yearMonth) {
        Map<Long, ExpenseTotals> expenseByUser = new HashMap<>();
        for (TravelExpense expense : travelExpenses) {
            if (isApproved(expense.getStatus())) {
                expenseByUser.computeIfAbsent(expense.getUser().getId(), ignored -> new ExpenseTotals())
                        .addTravel(expense.getType(), expense.getAmount());
            }
        }
        for (GeneralExpense expense : generalExpenses) {
            if (isApproved(expense.getStatus())) {
                expenseByUser.computeIfAbsent(expense.getUser().getId(), ignored -> new ExpenseTotals())
                        .addGeneral(expense.getAmount());
            }
        }

        Sheet sheet = workbook.createSheet(yearMonth.toString());
        writeTitle(sheet, yearMonth, "勤怠・経費集計（経費は承認済のみ）");
        writeHeader(sheet, styles, SUMMARY_HEADERS);

        List<User> orderedUsers = users.stream()
                .sorted(Comparator.comparing(User::getEmployeeId, Comparator.nullsLast(String::compareTo)))
                .toList();
        int rowNumber = HEADER_ROW + 1;
        for (User user : orderedUsers) {
            AttendanceService.MonthlyStatistics totals = attendanceService.calculateMonthlyStatistics(
                    attendanceByUser.getOrDefault(user.getId(), List.of()), yearMonth);
            ExpenseTotals expenses = expenseByUser.getOrDefault(user.getId(), new ExpenseTotals());
            Row row = sheet.createRow(rowNumber++);
            row.createCell(0).setCellValue(textOf(user.getEmployeeId()));
            row.createCell(1).setCellValue(textOf(user.getName()));
            row.createCell(2).setCellValue(textOf(user.getDepartment()));
            row.createCell(3).setCellValue(totals.workDays());
            setNumber(row, 4, totals.workHours(), styles.hours);
            setNumber(row, 5, totals.overtimeHours(), styles.hours);
            setNumber(row, 6, expenses.nonTaxableTravel, styles.amount);
            setNumber(row, 7, expenses.travel, styles.amount);
            setNumber(row, 8, expenses.general, styles.amount);
            setNumber(row, 9, expenses.total(), styles.amount);
        }
        finishSheet(sheet, SUMMARY_HEADERS.size(), rowNumber);
    }

    private void writeTravelExpenseSheet(Workbook workbook, Styles styles,
                                         List<TravelExpense> expenses, YearMonth yearMonth) {
        Sheet sheet = workbook.createSheet("交通費明細");
        writeTitle(sheet, yearMonth, "交通費明細");
        writeHeader(sheet, styles, TRAVEL_HEADERS);

        int rowNumber = HEADER_ROW + 1;
        BigDecimal approvedTotal = BigDecimal.ZERO;
        for (TravelExpense expense : expenses) {
            Row row = sheet.createRow(rowNumber++);
            setDate(row, 0, expense.getExpenseDate(), styles.date);
            writeUserColumns(row, 1, expense.getUser());
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
        finishSheet(sheet, TRAVEL_HEADERS.size(), dataEnd);
    }

    private void writeGeneralExpenseSheet(Workbook workbook, Styles styles,
                                          List<GeneralExpense> expenses, YearMonth yearMonth) {
        Sheet sheet = workbook.createSheet("諸経費明細");
        writeTitle(sheet, yearMonth, "諸経費明細");
        writeHeader(sheet, styles, GENERAL_HEADERS);

        int rowNumber = HEADER_ROW + 1;
        BigDecimal approvedTotal = BigDecimal.ZERO;
        for (GeneralExpense expense : expenses) {
            Row row = sheet.createRow(rowNumber++);
            setDate(row, 0, expense.getDate(), styles.date);
            writeUserColumns(row, 1, expense.getUser());
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
        finishSheet(sheet, GENERAL_HEADERS.size(), dataEnd);
    }

    private void writeTitle(Sheet sheet, YearMonth yearMonth, String title) {
        sheet.createRow(0).createCell(0)
                .setCellValue(yearMonth.getYear() + "年" + yearMonth.getMonthValue() + "月 " + title);
    }

    private void writeHeader(Sheet sheet, Styles styles, List<String> headers) {
        Row headerRow = sheet.createRow(HEADER_ROW);
        for (int column = 0; column < headers.size(); column++) {
            Cell cell = headerRow.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(styles.header);
        }
    }

    private void writeUserColumns(Row row, int firstColumn, User user) {
        row.createCell(firstColumn).setCellValue(textOf(user.getEmployeeId()));
        row.createCell(firstColumn + 1).setCellValue(textOf(user.getName()));
        row.createCell(firstColumn + 2).setCellValue(textOf(user.getDepartment()));
    }

    private void writeApprovedTotal(Sheet sheet, Styles styles, int rowNumber, int labelColumn, BigDecimal total) {
        Row row = sheet.createRow(rowNumber);
        Cell label = row.createCell(labelColumn);
        label.setCellValue(APPROVED_TOTAL_LABEL);
        label.setCellStyle(styles.bold);
        setNumber(row, labelColumn + 1, total, styles.boldAmount);
    }

    /** 列幅の自動調整、ヘッダー行の固定、フィルター範囲はデータ行のみ（末尾の合計行は含まない） */
    private void finishSheet(Sheet sheet, int columnCount, int dataEndExclusive) {
        for (int column = 0; column < columnCount; column++) {
            sheet.autoSizeColumn(column);
        }
        sheet.createFreezePane(0, HEADER_ROW + 1);
        sheet.setAutoFilter(new CellRangeAddress(
                HEADER_ROW, Math.max(HEADER_ROW, dataEndExclusive - 1), 0, columnCount - 1));
    }

    private void setNumber(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(style);
    }

    private void setDate(Row row, int column, LocalDate date, CellStyle style) {
        Cell cell = row.createCell(column);
        if (date != null) {
            cell.setCellValue(date);
            cell.setCellStyle(style);
        }
    }

    private static boolean isApproved(ApplicationStatus status) {
        return status == ApplicationStatus.APPROVED;
    }

    private static String statusOf(ApplicationStatus status) {
        return status == null ? "" : status.getDisplayName();
    }

    private static String employeeIdOf(User user) {
        return user == null || user.getEmployeeId() == null ? "" : user.getEmployeeId();
    }

    private static String textOf(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
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
            headerFont.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

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
