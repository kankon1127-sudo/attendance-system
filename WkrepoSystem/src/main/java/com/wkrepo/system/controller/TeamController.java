package com.wkrepo.system.controller;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.Agreement36Service;
import com.wkrepo.system.service.AttendanceService;
import com.wkrepo.system.service.HolidayService;
import com.wkrepo.system.service.TeamService;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 上長のチーム一覧と、部下の勤怠（参照のみ）。部長・管理者のみ。
 * 部下 = manager_id をたどった直属・間接の部下（ADMIN は全員）。
 */
@Controller
public class TeamController {

    private final TeamService teamService;
    private final AttendanceService attendanceService;
    private final Agreement36Service agreement36Service;
    private final HolidayService holidayService;
    private final UserRepository userRepository;
    private final MessageSource messageSource;

    public TeamController(TeamService teamService, AttendanceService attendanceService,
                          Agreement36Service agreement36Service, HolidayService holidayService,
                          UserRepository userRepository, MessageSource messageSource) {
        this.teamService = teamService;
        this.attendanceService = attendanceService;
        this.agreement36Service = agreement36Service;
        this.holidayService = holidayService;
        this.userRepository = userRepository;
        this.messageSource = messageSource;
    }

    @GetMapping("/team")
    public String team(@RequestParam(name = "year", required = false) Integer year,
                       @RequestParam(name = "month", required = false) Integer month,
                       @RequestParam(name = "attention", defaultValue = "false") boolean attentionOnly,
                       HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (!TeamService.canUseTeamView(user)) {
            return "redirect:/home?error=permission";
        }
        YearMonth target = targetMonth(year, month);
        List<TeamService.MemberSummary> summaries = teamService.summarize(user, target);
        long attentionCount = summaries.stream().filter(TeamService.MemberSummary::needsAttention).count();
        if (attentionOnly) {
            summaries = summaries.stream().filter(TeamService.MemberSummary::needsAttention).toList();
        }
        addMonthNavigation(model, target);
        model.addAttribute("summaries", summaries);
        model.addAttribute("attentionCount", attentionCount);
        model.addAttribute("attentionOnly", attentionOnly);
        model.addAttribute("user", user);
        return "team";
    }

    @GetMapping("/team/attendance")
    public String memberAttendance(@RequestParam("userId") Long userId,
                                   @RequestParam(name = "year", required = false) Integer year,
                                   @RequestParam(name = "month", required = false) Integer month,
                                   HttpSession session, Model model, Locale locale) {
        User user = (User) session.getAttribute("user");
        if (!TeamService.canUseTeamView(user)) {
            return "redirect:/home?error=permission";
        }
        // チームの一員でなければ見せない（URL の userId を書き換えても他部署の勤怠は見えない）
        if (!teamService.isMember(user, userId)) {
            return "redirect:/team?error=permission";
        }
        User member = userRepository.findById(userId).orElse(null);
        if (member == null) {
            return "redirect:/team";
        }
        YearMonth target = targetMonth(year, month);
        List<Attendance> attendances = attendanceService.getAttendancesByUserAndDateRange(
                member, target.atDay(1), target.atEndOfMonth());
        Map<LocalDate, Attendance> byDate = new HashMap<>();
        for (Attendance attendance : attendances) {
            byDate.put(attendance.getDate(), attendance);
        }
        List<Map<String, Object>> days = new ArrayList<>();
        for (int day = 1; day <= target.lengthOfMonth(); day++) {
            LocalDate date = target.atDay(day);
            Map<String, Object> row = new HashMap<>();
            row.put("date", date);
            row.put("weekday", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale));
            row.put("sunday", date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY);
            row.put("saturday", date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY);
            row.put("restDay", holidayService.isRestDay(date));
            row.put("holidayName", holidayService.getHolidayName(date).orElse(null));
            row.put("attendance", byDate.get(date));
            days.add(row);
        }
        Agreement36Service.Result agreement36 = agreement36Service.check(member, target);
        List<String> agreement36Messages = agreement36.alerts().stream()
                .map(alert -> messageSource.getMessage(alert.messageKey(), alert.args(), locale))
                .toList();
        List<String> anomalyMessages = attendanceService.findMonthlyAnomalies(member, attendances).stream()
                .map(anomaly -> anomaly.getDate() + " " + messageSource.getMessage(anomaly.getMessageKey(), null, locale))
                .toList();

        addMonthNavigation(model, target);
        model.addAttribute("member", member);
        model.addAttribute("days", days);
        model.addAttribute("statistics", attendanceService.calculateMonthlyStatistics(attendances, target));
        model.addAttribute("agreement36", agreement36);
        model.addAttribute("agreement36Messages", agreement36Messages);
        model.addAttribute("anomalyMessages", anomalyMessages);
        model.addAttribute("monthLocked", attendanceService.isMonthLocked(member, target.getYear(), target.getMonthValue()));
        model.addAttribute("user", user);
        return "team-attendance";
    }

    /** 未来の月は当月にする */
    private YearMonth targetMonth(Integer year, Integer month) {
        YearMonth current = YearMonth.now();
        if (year == null || month == null || month < 1 || month > 12) {
            return current;
        }
        YearMonth requested = YearMonth.of(year, month);
        return requested.isAfter(current) ? current : requested;
    }

    private void addMonthNavigation(Model model, YearMonth target) {
        YearMonth prev = target.minusMonths(1);
        YearMonth next = target.plusMonths(1);
        model.addAttribute("year", target.getYear());
        model.addAttribute("month", target.getMonthValue());
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());
        model.addAttribute("hasNext", !next.isAfter(YearMonth.now()));
    }
}
