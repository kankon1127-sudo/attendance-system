package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.dto.AttendanceAnomaly;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.Agreement36Service;
import com.wkrepo.system.service.AttendanceService;
import com.wkrepo.system.service.AttendanceExcelExportService;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import com.wkrepo.system.service.GeneralExpenseService;
import com.wkrepo.system.service.HolidayService;
import com.wkrepo.system.service.TravelExpenseService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
@RequestMapping("/attendance")
public class AttendanceController {

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private HolidayService holidayService;
    
    @Autowired
    private DefaultAttendanceSettingService defaultAttendanceSettingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AttendanceExcelExportService attendanceExcelExportService;

    @Autowired
    private TravelExpenseService travelExpenseService;

    @Autowired
    private GeneralExpenseService generalExpenseService;

    @Autowired
    private Agreement36Service agreement36Service;

    @Autowired
    private Messages messages;

    /**
     * 1. 勤怠入力 画面表示処理
     */
    @GetMapping
    public String showAttendance(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            HttpSession session, Model model, Locale locale) {

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        LocalDate today = LocalDate.now();
        int currentYear = (year != null) ? year : today.getYear();
        int currentMonth = (month != null) ? month : today.getMonthValue();

        // 年月パラメータを検証し、未来の月へのアクセスは許可しない
        YearMonth requestYearMonth = YearMonth.of(currentYear, currentMonth);
        YearMonth currentYearMonth = YearMonth.from(today);
        if (requestYearMonth.isAfter(currentYearMonth)) {
            // 当月にリダイレクト
            return "redirect:/attendance?year=" + today.getYear() + "&month=" + today.getMonthValue();
        }

        YearMonth yearMonth = requestYearMonth;
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        List<Attendance> attendances = attendanceService.getAttendancesByUserAndDateRange(user, startDate, endDate);
        Map<LocalDate, Attendance> attendanceMap = new HashMap<>();
        for (Attendance a : attendances) {
            attendanceMap.put(a.getDate(), a);
        }

        List<Map<String, Object>> calendarDays = new ArrayList<>();
        for (int i = 1; i <= yearMonth.lengthOfMonth(); i++) {
            LocalDate date = yearMonth.atDay(i);
            Map<String, Object> dayMap = new HashMap<>();
            dayMap.put("day", i);
            dayMap.put("date", date.toString());
            dayMap.put("dateString", date.format(DateTimeFormatter.ofPattern("MM-dd")));
            dayMap.put("dayOfWeekName", getDayOfWeekName(date, locale));
            
            dayMap.put("sunday", date.getDayOfWeek() == DayOfWeek.SUNDAY);
            dayMap.put("saturday", date.getDayOfWeek() == DayOfWeek.SATURDAY);
            dayMap.put("holiday", holidayService.isRestDay(date)); // 共通の休日判定ロジックを使用

            // DB の実レコードを読み込む
            Attendance attendance = attendanceMap.get(date);
            dayMap.put("attendance", attendance);

            calendarDays.add(dayMap);
        }

        AttendanceService.MonthlyStatistics statistics =
                attendanceService.calculateMonthlyStatistics(attendances, yearMonth);
        List<AttendanceAnomaly> anomalies = attendanceService.findMonthlyAnomalies(user, attendances);
        
        model.addAttribute("calendarDays", calendarDays);
        model.addAttribute("totalWorkDays", statistics.workDays());
        model.addAttribute("totalWorkHours", statistics.workHours());
        model.addAttribute("totalOvertimeHours", statistics.overtimeHours());
        model.addAttribute("attendanceAnomalies", anomalies);
        model.addAttribute("agreement36", agreement36Service.check(user, yearMonth));
        model.addAttribute("currentYear", currentYear);
        model.addAttribute("currentMonth", currentMonth);
        model.addAttribute("user", user);
        model.addAttribute("attendanceLocked", attendanceService.isMonthLocked(user, currentYear, currentMonth));

        // ページングボタンの処理
        YearMonth prev = yearMonth.minusMonths(1);
        YearMonth next = yearMonth.plusMonths(1);
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());
        model.addAttribute("showNextMonth", !yearMonth.isAfter(YearMonth.from(today)));

        // ユーザーの最初の勤怠レコードから当月までの月リストを生成
        YearMonth currentYearMonthForModel = YearMonth.from(today);
        YearMonth startYearMonth = attendanceService.getFirstAttendanceYearMonth(user);
        if (startYearMonth == null) {
            // 勤怠レコードがない場合は当月から 12 か月前までを使う
            startYearMonth = currentYearMonthForModel.minusMonths(12);
        }
        
        List<Map<String, Integer>> availableMonths = new ArrayList<>();
        YearMonth temp = startYearMonth;
        while (!temp.isAfter(currentYearMonthForModel)) {
            Map<String, Integer> monthInfo = new HashMap<>();
            monthInfo.put("year", temp.getYear());
            monthInfo.put("month", temp.getMonthValue());
            availableMonths.add(monthInfo);
            temp = temp.plusMonths(1);
        }
                model.addAttribute("availableMonths", availableMonths);

        // デフォルト勤怠設定を取得して Model に追加
        DefaultAttendanceSetting defaultSetting = defaultAttendanceSettingService.getOrCreateDefaultSetting(user);
        model.addAttribute("defaultSetting", defaultSetting);

        return "attendance";
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportMonthlyAttendance(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            HttpSession session) {
        User currentUser = (User) session.getAttribute("user");
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        LocalDate today = LocalDate.now();
        YearMonth yearMonth;
        try {
            yearMonth = YearMonth.of(year == null ? today.getYear() : year,
                    month == null ? today.getMonthValue() : month);
        } catch (java.time.DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid attendance export month", exception);
        }
        if (yearMonth.isAfter(YearMonth.from(today))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Future attendance cannot be exported");
        }
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        List<User> visibleUsers = userRepository.findAll().stream()
                .filter(user -> canViewAttendance(currentUser, user))
                .toList();
        List<Attendance> attendances = attendanceService.getAllAttendancesByDateRange(startDate, endDate);
        List<TravelExpense> travelExpenses = travelExpenseService.getAllTravelExpensesByDateRange(startDate, endDate);
        List<GeneralExpense> generalExpenses = generalExpenseService.getAllGeneralExpensesByDateRange(startDate, endDate);

        try {
            // 経費は visibleUsers で絞り込む（表示範囲は勤怠と同じ）
            byte[] workbook = attendanceExcelExportService.exportMonthlyAttendance(
                    visibleUsers, attendances, travelExpenses, generalExpenses, yearMonth);
            String filename = "attendance-" + yearMonth + ".xlsx";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(filename, StandardCharsets.UTF_8)
                    .build());
            return new ResponseEntity<>(workbook, headers, HttpStatus.OK);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate the attendance workbook", exception);
        }
    }

    private boolean canViewAttendance(User currentUser, User targetUser) {
        if ("ADMIN".equals(currentUser.getRole()) || "SOUMU".equals(currentUser.getRole())) {
            return true;
        }
        if ("MANAGER".equals(currentUser.getRole())) {
            return currentUser.getDepartment() != null
                    && currentUser.getDepartment().equals(targetUser.getDepartment());
        }
        return currentUser.getId().equals(targetUser.getId());
    }

    /**
     * 2. 保存処理：既存レコードを検索してから更新（UPDATE）し、不正なデータを残さない
     */
    @PostMapping("/saveInline")
    public String saveInline(
            @RequestParam("date") String dateStr,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "breakTime", required = false) String breakTime,
            @RequestParam(value = "agreement36Status", required = false) String status,
            @RequestParam(value = "remarks", required = false) String remarks,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month,
            HttpSession session) {

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        LocalDate date = LocalDate.parse(dateStr);
        if (attendanceService.isMonthLocked(user, date.getYear(), date.getMonthValue())) {
            return "redirect:/attendance?year=" + date.getYear() + "&month=" + date.getMonthValue() + "&locked=true";
        }
        Attendance attendance = attendanceService.getAttendanceByUserAndDate(user, date);
        if (attendance == null) {
            attendance = new Attendance(user, date);
        }

        // 勤怠データを更新
        attendance.setStartTime(startTime != null && !startTime.isEmpty() ? LocalTime.parse(startTime) : null);
        attendance.setEndTime(endTime != null && !endTime.isEmpty() ? LocalTime.parse(endTime) : null);
        attendance.setBreakTime(breakTime != null && !breakTime.isEmpty() ? LocalTime.parse(breakTime) : null);
        attendance.setAgreement36Status(status);
        attendance.setRemarks(remarks);
        // 実働時間と残業時間はサーバー側で計算し、フロントエンドの値は受け取らない

        attendanceService.updateAttendance(attendance);

        String redirectUrl = "/attendance";
        if (year != null && month != null) {
            redirectUrl += "?year=" + year + "&month=" + month;
        }

        return "redirect:" + redirectUrl;
    }

    /**
     * 3. 勤怠編集画面へ遷移
     */
    @GetMapping("/edit/{date}")
    public String showEditPage(@PathVariable("date") String dateStr, HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        LocalDate date = LocalDate.parse(dateStr);
        Attendance attendance = attendanceService.getAttendanceByUserAndDate(user, date);
        DefaultAttendanceSetting defaultSetting = defaultAttendanceSettingService.getOrCreateDefaultSetting(user);

        model.addAttribute("date", dateStr);
        model.addAttribute("attendance", attendance);
        model.addAttribute("defaultSetting", defaultSetting);
        model.addAttribute("user", user);
        model.addAttribute("attendanceLocked", attendanceService.isMonthLocked(user, date.getYear(), date.getMonthValue()));

        return "attendance-edit";
    }

    /**
     * 4. 勤怠編集画面の保存
     */
    @PostMapping("/edit")
    public String saveEditPage(
            @RequestParam("date") String dateStr,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "breakTime", required = false) String breakTime,
            @RequestParam(value = "remarks", required = false) String remarks,
            HttpSession session) {

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        LocalDate date = LocalDate.parse(dateStr);
        if (attendanceService.isMonthLocked(user, date.getYear(), date.getMonthValue())) {
            return "redirect:/attendance?year=" + date.getYear() + "&month=" + date.getMonthValue() + "&locked=true";
        }
        Attendance attendance = attendanceService.getAttendanceByUserAndDate(user, date);
        if (attendance == null) {
            attendance = new Attendance(user, date);
        }

        attendance.setStartTime(startTime != null && !startTime.isEmpty() ? LocalTime.parse(startTime) : null);
        attendance.setEndTime(endTime != null && !endTime.isEmpty() ? LocalTime.parse(endTime) : null);
        attendance.setBreakTime(breakTime != null && !breakTime.isEmpty() ? LocalTime.parse(breakTime) : null);
        attendance.setRemarks(remarks);

        attendanceService.updateAttendance(attendance);

        return "redirect:/attendance?year=" + date.getYear() + "&month=" + date.getMonthValue();
    }

    /**
     * 5. デフォルト設定の取得
     */
        @GetMapping("/getDefaultSetting")
    @ResponseBody
    public DefaultAttendanceSetting getDefaultSetting(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return null;
        }

        return defaultAttendanceSettingService.getOrCreateDefaultSetting(user);
    }

    /**
     * 6. 一括デフォルト入力
     */
    @PostMapping("/bulkApplyDefaults")
    @ResponseBody
    public Map<String, Object> bulkApplyDefaults(
            @RequestParam("year") int year,
            @RequestParam("month") int month,
            HttpSession session) {
        
        Map<String, Object> response = new HashMap<>();
        User user = (User) session.getAttribute("user");
        if (user == null) {
            response.put("success", false);
            response.put("message", messages.get("common.error.sessionTimeout"));
            return response;
        }

        try {
            if (attendanceService.isMonthLocked(user, year, month)) {
                response.put("success", false);
                response.put("message", messages.get("attendance.error.monthLocked"));
                return response;
            }
            DefaultAttendanceSetting setting = defaultAttendanceSettingService.getOrCreateDefaultSetting(user);
            attendanceService.bulkApplyDefaults(user, year, month, setting);
            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", messages.get("common.error.withDetail", messages.of(e)));
        }
        return response;
    }

    private String getDayOfWeekName(LocalDate date, Locale locale) {
        return date.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, locale);
    }
}