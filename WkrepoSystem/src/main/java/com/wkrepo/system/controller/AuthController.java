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
import java.time.LocalDate;
import java.time.YearMonth;
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
        model.addAttribute("pendingApprovals", pendingApprovals.stream().limit(5).toList());

        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        List<Attendance> attendances = attendanceService.getAttendancesByUserAndDateRange(
                user, currentMonth.atDay(1), currentMonth.atEndOfMonth());
        AttendanceService.MonthlyStatistics statistics =
                attendanceService.calculateMonthlyStatistics(attendances, currentMonth);
        model.addAttribute("homeWorkHours", statistics.workHours());
        model.addAttribute("homeOvertimeHours", statistics.overtimeHours());
        model.addAttribute("homeWorkDays", statistics.workDays());
        model.addAttribute("homeRemainingLeave",
                paidLeaveService.getBalance(user, today.getYear()).remainingDays());
        return "home";
    }
}
