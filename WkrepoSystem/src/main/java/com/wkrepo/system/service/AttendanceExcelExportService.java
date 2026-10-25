package com.wkrepo.system.service;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.User;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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

@Service
public class AttendanceExcelExportService {
    private static final List<String> HEADERS = List.of(
            "社員番号", "氏名", "部署", "出勤日数", "実働時間（時間）", "残業時間（時間）");

    private final HolidayService holidayService;

    public AttendanceExcelExportService(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    public byte[] exportMonthlyAttendance(List<User> users, List<Attendance> attendances, YearMonth yearMonth)
            throws IOException {
        Map<Long, List<Attendance>> attendanceByUser = new HashMap<>();
        for (Attendance attendance : attendances) {
            attendanceByUser.computeIfAbsent(attendance.getUser().getId(), ignored -> new ArrayList<>()).add(attendance);
        }

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(yearMonth.toString());
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle hourStyle = workbook.createCellStyle();
            hourStyle.setDataFormat(workbook.createDataFormat().getFormat("0.00"));

            Row titleRow = sheet.createRow(0);
            titleRow.createCell(0).setCellValue(yearMonth.getYear() + "年" + yearMonth.getMonthValue() + "月 勤怠集計");
            Row headerRow = sheet.createRow(2);
            for (int column = 0; column < HEADERS.size(); column++) {
                Cell cell = headerRow.createCell(column);
                cell.setCellValue(HEADERS.get(column));
                cell.setCellStyle(headerStyle);
            }

            List<User> orderedUsers = users.stream()
                    .sorted(Comparator.comparing(User::getEmployeeId, Comparator.nullsLast(String::compareTo)))
                    .toList();
            int rowNumber = 3;
            for (User user : orderedUsers) {
                EmployeeTotals totals = calculateTotals(attendanceByUser.getOrDefault(user.getId(), List.of()));
                Row row = sheet.createRow(rowNumber++);
                row.createCell(0).setCellValue(user.getEmployeeId() == null ? "" : user.getEmployeeId());
                row.createCell(1).setCellValue(user.getName() == null ? "" : user.getName());
                row.createCell(2).setCellValue(user.getDepartment() == null ? "" : user.getDepartment());
                row.createCell(3).setCellValue(totals.attendanceDays());
                row.createCell(4).setCellValue(totals.workHours().doubleValue());
                row.getCell(4).setCellStyle(hourStyle);
                row.createCell(5).setCellValue(totals.overtimeHours().doubleValue());
                row.getCell(5).setCellStyle(hourStyle);
            }

            for (int column = 0; column < HEADERS.size(); column++) {
                sheet.autoSizeColumn(column);
            }
            sheet.createFreezePane(0, 3);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(2, Math.max(2, rowNumber - 1), 0, HEADERS.size() - 1));
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private EmployeeTotals calculateTotals(List<Attendance> attendances) {
        BigDecimal workHours = BigDecimal.ZERO;
        BigDecimal overtimeHours = BigDecimal.ZERO;
        int attendanceDays = 0;

        for (Attendance attendance : attendances) {
            String status = attendance.getAgreement36Status();
            if (status == null || status.isBlank() || "-".equals(status)
                    || "欠勤".equals(status) || "有給".equals(status)) {
                continue;
            }

            BigDecimal dailyWorkHours = valueOrZero(attendance.getWorkHours());
            BigDecimal dailyOvertimeHours = valueOrZero(attendance.getOvertimeHours());
            LocalDate date = attendance.getDate();
            if (holidayService.isRestDay(date)) {
                if (dailyWorkHours.compareTo(BigDecimal.ZERO) > 0) {
                    overtimeHours = overtimeHours.add(dailyWorkHours);
                }
                if (dailyOvertimeHours.compareTo(BigDecimal.ZERO) > 0) {
                    overtimeHours = overtimeHours.add(dailyOvertimeHours);
                }
            } else {
                if (dailyWorkHours.compareTo(BigDecimal.ZERO) > 0) {
                    workHours = workHours.add(dailyWorkHours);
                }
                if (dailyOvertimeHours.compareTo(BigDecimal.ZERO) > 0) {
                    overtimeHours = overtimeHours.add(dailyOvertimeHours);
                }
            }

            if ("出社".equals(status) || "在宅".equals(status)) {
                attendanceDays++;
            }
        }

        return new EmployeeTotals(attendanceDays, workHours, overtimeHours);
    }

    private BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        style.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private record EmployeeTotals(int attendanceDays, BigDecimal workHours, BigDecimal overtimeHours) {}
}
