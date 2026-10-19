package com.wkrepo.system.controller;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.AttendanceService;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import com.wkrepo.system.service.HolidayService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 1. 勤怠入力 画面表示逻辑
     */
    @GetMapping
    public String showAttendance(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            HttpSession session, Model model) {

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        LocalDate today = LocalDate.now();
        int currentYear = (year != null) ? year : today.getYear();
        int currentMonth = (month != null) ? month : today.getMonthValue();

        // 验证年月参数，不允许访问未来月份
        YearMonth requestYearMonth = YearMonth.of(currentYear, currentMonth);
        YearMonth currentYearMonth = YearMonth.from(today);
        if (requestYearMonth.isAfter(currentYearMonth)) {
            // 重定向到当前月份
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

        // 统计数据初始化
        BigDecimal totalWorkHours = BigDecimal.ZERO;
        BigDecimal totalOvertimeHours = BigDecimal.ZERO;
        int totalWorkDays = 0;

        List<Map<String, Object>> calendarDays = new ArrayList<>();
        for (int i = 1; i <= yearMonth.lengthOfMonth(); i++) {
            LocalDate date = yearMonth.atDay(i);
            Map<String, Object> dayMap = new HashMap<>();
            dayMap.put("day", i);
            dayMap.put("date", date.toString());
            dayMap.put("dateString", date.format(DateTimeFormatter.ofPattern("MM-dd")));
            dayMap.put("dayOfWeekName", getDayOfWeekName(date));
            
            dayMap.put("sunday", date.getDayOfWeek() == DayOfWeek.SUNDAY);
            dayMap.put("saturday", date.getDayOfWeek() == DayOfWeek.SATURDAY);
            dayMap.put("holiday", holidayService.isRestDay(date)); // 使用统一的节假日判定逻辑

            // 装载数据库中の真实记录
            Attendance attendance = attendanceMap.get(date);
            dayMap.put("attendance", attendance);

            // ================= 终极核能统计网（解决多累计的关键） =================
            if (attendance != null) {
                BigDecimal hours = attendance.getWorkHours();
                BigDecimal overtime = attendance.getOvertimeHours();
                String status = attendance.getAgreement36Status();

                // 核心过滤条件：状态不能是 "-", "欠勤" 或 "有給"
                boolean isExcludedStatus = "-".equals(status) || "欠勤".equals(status) || "有給".equals(status);
                
                if (!isExcludedStatus && status != null && !status.trim().isEmpty()) {
                    // 确保工时字段不为null，否则视为0
                    if (hours == null) hours = BigDecimal.ZERO;
                    if (overtime == null) overtime = BigDecimal.ZERO;

                    // 判断是否为周末或节假日
                    boolean isWeekendOrHoliday = holidayService.isRestDay(date);
                    
                    if (isWeekendOrHoliday) {
                        // 周末或节假日出勤：实际工作时间全部算作加班时间
                        if (hours != null && hours.compareTo(BigDecimal.ZERO) > 0) {
                            totalOvertimeHours = totalOvertimeHours.add(hours);
                        }
                        // 如果原本就有加班时间，也要加上
                        if (overtime != null && overtime.compareTo(BigDecimal.ZERO) > 0) {
                            totalOvertimeHours = totalOvertimeHours.add(overtime);
                        }
                    } else {
                        // 工作日：正常分别计算工作时间和加班时间
                        if (hours != null && hours.compareTo(BigDecimal.ZERO) > 0) {
                            totalWorkHours = totalWorkHours.add(hours);
                        }
                        if (overtime != null && overtime.compareTo(BigDecimal.ZERO) > 0) {
                            totalOvertimeHours = totalOvertimeHours.add(overtime);
                        }
                    }
                    
                    // 只有"出社"或"在宅"状态才计入出勤天数
                    if ("出社".equals(status) || "在宅".equals(status)) {
                        totalWorkDays++;
                    }
                }
                // 注意：这里去掉了 else if (isExcludedStatus) の continue 语句
                // 这样即使遇到排除状态，日期也会被添加到日历中
            }
            // ===================================================================
            calendarDays.add(dayMap);
        }

        // 最終確保統計數據正確性
        if (totalWorkHours == null) totalWorkHours = BigDecimal.ZERO;
        if (totalOvertimeHours == null) totalOvertimeHours = BigDecimal.ZERO;
        
        model.addAttribute("calendarDays", calendarDays);
        model.addAttribute("totalWorkDays", totalWorkDays);
        model.addAttribute("totalWorkHours", totalWorkHours);
        model.addAttribute("totalOvertimeHours", totalOvertimeHours);
        model.addAttribute("currentYear", currentYear);
        model.addAttribute("currentMonth", currentMonth);
        model.addAttribute("user", user);

        // 分页按钮逻辑
        YearMonth prev = yearMonth.minusMonths(1);
        YearMonth next = yearMonth.plusMonths(1);
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());
        model.addAttribute("showNextMonth", !yearMonth.isAfter(YearMonth.from(today)));

        // 生成从用户最早考勤记录到当前月份の月份リスト
        YearMonth currentYearMonthForModel = YearMonth.from(today);
        YearMonth startYearMonth = attendanceService.getFirstAttendanceYearMonth(user);
        if (startYearMonth == null) {
            // 如果没有考勤记录，则使用当前月份往前推12个月
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

        // 新增代码：获取并添加默认考勤设置到Model
        DefaultAttendanceSetting defaultSetting = defaultAttendanceSettingService.getOrCreateDefaultSetting(user);
        model.addAttribute("defaultSetting", defaultSetting);

        return "attendance";
    }

    /**
     * 2. 保存逻辑：先查后改（UPDATE），完美抹除脏数据
     */
    @PostMapping("/saveInline")
    public String saveInline(
            @RequestParam("date") String dateStr,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "breakTime", required = false) String breakTime,
            @RequestParam(value = "agreement36Status", required = false) String status,
            @RequestParam(value = "remarks", required = false) String remarks,
            @RequestParam(value = "workHours", required = false) BigDecimal workHours,
            @RequestParam(value = "overtimeHours", required = false) BigDecimal overtimeHours,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month,
            HttpSession session) {

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        LocalDate date = LocalDate.parse(dateStr);
        Attendance attendance = attendanceService.getAttendanceByUserAndDate(user, date);
        if (attendance == null) {
            attendance = new Attendance(user, date);
        }

        // 更新考勤数据
        attendance.setStartTime(startTime != null && !startTime.isEmpty() ? LocalTime.parse(startTime) : null);
        attendance.setEndTime(endTime != null && !endTime.isEmpty() ? LocalTime.parse(endTime) : null);
        attendance.setBreakTime(breakTime != null && !breakTime.isEmpty() ? LocalTime.parse(breakTime) : null);
        attendance.setAgreement36Status(status);
        attendance.setRemarks(remarks);
        attendance.setWorkHours(workHours);
        attendance.setOvertimeHours(overtimeHours);

        attendanceService.updateAttendance(attendance);

        String redirectUrl = "/attendance";
        if (year != null && month != null) {
            redirectUrl += "?year=" + year + "&month=" + month;
        }

        return "redirect:" + redirectUrl;
    }

    /**
     * 3. 跳转到考勤编辑页面
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

        return "attendance-edit";
    }

    /**
     * 4. 保存考勤编辑页面
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
     * 5. 获取默认设置
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
            response.put("message", "セッションがタイムアウトしました。");
            return response;
        }

        try {
            DefaultAttendanceSetting setting = defaultAttendanceSettingService.getOrCreateDefaultSetting(user);
            attendanceService.bulkApplyDefaults(user, year, month, setting);
            response.put("success", true);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "エラーが発生しました: " + e.getMessage());
        }
        return response;
    }

    private String getDayOfWeekName(LocalDate date) {
        switch (date.getDayOfWeek()) {
            case MONDAY: return "月";
            case TUESDAY: return "火";
            case WEDNESDAY: return "水";
            case THURSDAY: return "木";
            case FRIDAY: return "金";
            case SATURDAY: return "土";
            case SUNDAY: return "日";
            default: return "";
        }
    }
}