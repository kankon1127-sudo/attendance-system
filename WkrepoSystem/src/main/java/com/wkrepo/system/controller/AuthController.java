package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.service.Agreement36Service;
import com.wkrepo.system.service.AttendanceService;
import com.wkrepo.system.service.ApplicationListService;
import com.wkrepo.system.service.PaidLeaveService;
import com.wkrepo.system.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;

@Controller
public class AuthController {
    
    private static final String SESSION_USER = "user";
    private final UserService userService;
    private final ApplicationListService applicationListService;
    private final AttendanceService attendanceService;
    private final PaidLeaveService paidLeaveService;
    private final Agreement36Service agreement36Service;
    private final Messages messages;

    @Autowired
    public AuthController(
            UserService userService,
            ApplicationListService applicationListService,
            AttendanceService attendanceService,
            PaidLeaveService paidLeaveService,
            Agreement36Service agreement36Service,
            Messages messages) {
        this.userService = userService;
        this.applicationListService = applicationListService;
        this.attendanceService = attendanceService;
        this.paidLeaveService = paidLeaveService;
        this.agreement36Service = agreement36Service;
        this.messages = messages;
    }
    
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
    
    @PostMapping("/login")
    public String login(@RequestParam String username,
                       @RequestParam String password,
                       HttpServletRequest request,
                       HttpSession session,
                       Model model) {
        try {
            User user = userService.login(username, password);
            // セッション固定攻撃の対策：ログイン前のセッション ID を使い続けないよう、ログイン成功時に ID を変える
            request.changeSessionId();
            session.setAttribute(SESSION_USER, user);
            return "redirect:/home";
        } catch (RuntimeException e) {
            model.addAttribute("error", messages.of(e));
            return "login";
        }
    }
    
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/login";
    }
    
    @GetMapping("/home")
    public String home(HttpSession session, Model model) {
        User user = (User) session.getAttribute(SESSION_USER);
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute(SESSION_USER, user);
        model.addAttribute("currentPage", "home");
        model.addAttribute("rejectedApplicationCount", applicationListService.countOwnRejected(user));
        List<ApplicationItem> pendingApprovals = applicationListService.getPendingApprovalsForUser(user);
        model.addAttribute("pendingApprovalCount", pendingApprovals.size());
        model.addAttribute("overdueApprovalCount",
                pendingApprovals.stream().filter(ApplicationItem::isOverdue).count());
        model.addAttribute("pendingApprovals", pendingApprovals.stream()
                .sorted(Comparator.comparing(
                        ApplicationItem::getApplicationDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(5)
                .toList());

        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        YearMonth firstTrendMonth = currentMonth.minusMonths(5);
        List<Attendance> attendances = attendanceService.getAttendancesByUserAndDateRange(
                user, firstTrendMonth.atDay(1), currentMonth.atEndOfMonth());
        List<AttendanceService.MonthlyTrend> monthlyTrend =
                attendanceService.calculateMonthlyTrend(attendances, firstTrendMonth, 6);
        AttendanceService.MonthlyTrend statistics = monthlyTrend.get(monthlyTrend.size() - 1);
        // 進捗バーは 36 協定の時間外労働（日曜日の休日労働を除く）で月 45 時間と比較する
        Agreement36Service.Result agreement36 = agreement36Service.check(user, currentMonth);
        BigDecimal monthlyOvertime = agreement36.monthlyOvertime();
        BigDecimal overtimeLimit = new BigDecimal("45.0");
        BigDecimal overtimeWarningThreshold = new BigDecimal("40.0");
        BigDecimal overtimePercent = monthlyOvertime
                .multiply(BigDecimal.valueOf(100))
                .divide(overtimeLimit, 0, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));
        model.addAttribute("homeWorkHours", statistics.workHours());
        model.addAttribute("homeOvertimeHours", statistics.overtimeHours());
        model.addAttribute("homeWorkDays", statistics.workDays());
        model.addAttribute("homeRemainingLeave",
                paidLeaveService.getBalance(user, today.getYear()).remainingDays());
        model.addAttribute("homeMonthlyTrend", monthlyTrend);
        model.addAttribute("homeAgreement36Overtime", monthlyOvertime);
        model.addAttribute("homeAgreement36", agreement36);
        // 休日労働（日曜日）と、時間外＋休日労働（法定上限は月 100 時間未満）。80 時間以上で黄、100 時間以上で赤
        BigDecimal monthlyHolidayWork = agreement36.monthlyHolidayWork();
        BigDecimal monthlyTotalWork = monthlyOvertime.add(monthlyHolidayWork);
        BigDecimal totalLimit = new BigDecimal("100");
        model.addAttribute("homeHolidayWork", monthlyHolidayWork);
        model.addAttribute("homeTotalWork", monthlyTotalWork);
        model.addAttribute("homeTotalLimit", totalLimit);
        model.addAttribute("homeTotalPercent", monthlyTotalWork
                .multiply(BigDecimal.valueOf(100))
                .divide(totalLimit, 0, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100)));
        model.addAttribute("homeTotalLevel", monthlyTotalWork.compareTo(totalLimit) >= 0 ? "danger"
                : monthlyTotalWork.compareTo(new BigDecimal("80")) >= 0 ? "warning" : "success");
        model.addAttribute("homeOvertimePercent", overtimePercent);
        model.addAttribute("homeOvertimeLimit", overtimeLimit);
        model.addAttribute("homeOvertimeRemaining",
                overtimeLimit.subtract(monthlyOvertime).max(BigDecimal.ZERO));
        model.addAttribute("homeOvertimeWarningReached",
                monthlyOvertime.compareTo(overtimeWarningThreshold) >= 0);
        return "home";
    }
}
