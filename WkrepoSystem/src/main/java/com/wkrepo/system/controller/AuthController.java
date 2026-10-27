package com.wkrepo.system.controller;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.entity.Attendance;
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

    @Autowired
    public AuthController(
            UserService userService,
            ApplicationListService applicationListService,
            AttendanceService attendanceService,
            PaidLeaveService paidLeaveService) {
        this.userService = userService;
        this.applicationListService = applicationListService;
        this.attendanceService = attendanceService;
        this.paidLeaveService = paidLeaveService;
    }
    
    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
    
    @PostMapping("/login")
    public String login(@RequestParam String username, 
                       @RequestParam String password,
                       HttpSession session,
                       Model model) {
        try {
            User user = userService.login(username, password);
            session.setAttribute(SESSION_USER, user);
            return "redirect:/home";
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
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
        model.addAttribute("pageTitle", "ホーム");
        model.addAttribute("currentPage", "home");
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
        BigDecimal overtimeLimit = new BigDecimal("45.0");
        BigDecimal overtimeWarningThreshold = new BigDecimal("40.0");
        BigDecimal overtimePercent = statistics.overtimeHours()
                .multiply(BigDecimal.valueOf(100))
                .divide(overtimeLimit, 0, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100));
        model.addAttribute("homeWorkHours", statistics.workHours());
        model.addAttribute("homeOvertimeHours", statistics.overtimeHours());
        model.addAttribute("homeWorkDays", statistics.workDays());
        model.addAttribute("homeRemainingLeave",
                paidLeaveService.getBalance(user, today.getYear()).remainingDays());
        model.addAttribute("homeMonthlyTrend", monthlyTrend);
        model.addAttribute("homeOvertimePercent", overtimePercent);
        model.addAttribute("homeOvertimeLimit", overtimeLimit);
        model.addAttribute("homeOvertimeRemaining",
                overtimeLimit.subtract(statistics.overtimeHours()).max(BigDecimal.ZERO));
        model.addAttribute("homeOvertimeWarningReached",
                statistics.overtimeHours().compareTo(overtimeWarningThreshold) >= 0);
        return "home";
    }
}
