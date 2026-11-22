package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.LeaveGrantPolicy;
import com.wkrepo.system.service.LeaveGrantService;
import com.wkrepo.system.service.PaidLeaveService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 年休管理（総務・管理者）：全員の残日数・次回の付与予定・年 5 日取得義務の状況、個人ごとの付与履歴と手動付与。
 */
@Controller
public class LeaveManagementController {

    private final UserRepository userRepository;
    private final PaidLeaveService paidLeaveService;
    private final LeaveGrantService leaveGrantService;
    private final Messages messages;

    public LeaveManagementController(UserRepository userRepository, PaidLeaveService paidLeaveService,
                                     LeaveGrantService leaveGrantService, Messages messages) {
        this.userRepository = userRepository;
        this.paidLeaveService = paidLeaveService;
        this.leaveGrantService = leaveGrantService;
        this.messages = messages;
    }

    public static boolean canUse(User user) {
        return user != null && ("SOUMU".equals(user.getRole()) || "ADMIN".equals(user.getRole()));
    }

    /** 一覧の 1 行 */
    public record Row(User user, PaidLeaveService.Balance balance, LeaveGrantPolicy.Schedule nextGrant,
                      LeaveGrantService.Obligation obligation, boolean proportional) {
        /** 対応が必要か：入社日未設定、年 5 日が期限 3 か月以内で未達・期限切れ */
        public boolean needsAction() {
            return user.getHireDate() == null || (obligation != null
                    && (obligation.status() == LeaveGrantService.ObligationStatus.WARNING
                        || obligation.status() == LeaveGrantService.ObligationStatus.OVERDUE));
        }
    }

    @GetMapping("/leave-management")
    public String list(@RequestParam(name = "action", defaultValue = "false") boolean actionOnly,
                       HttpSession session, Model model) {
        User current = (User) session.getAttribute("user");
        if (!canUse(current)) {
            return "redirect:/home?error=permission";
        }
        LocalDate today = leaveGrantService.today();
        List<Row> rows = new ArrayList<>();
        for (User user : userRepository.findAll()) {
            if ("INACTIVE".equals(user.getStatus())) {
                continue;
            }
            rows.add(new Row(user, paidLeaveService.getBalance(user, today), LeaveGrantPolicy.nextSchedule(user, today),
                    leaveGrantService.currentObligation(user), LeaveGrantPolicy.isProportional(user)));
        }
        rows.sort(Comparator.comparing((Row row) -> !row.needsAction())
                .thenComparing(row -> row.user().getEmployeeId(), Comparator.nullsLast(String::compareTo)));
        long actionCount = rows.stream().filter(Row::needsAction).count();
        model.addAttribute("rows", actionOnly ? rows.stream().filter(Row::needsAction).toList() : rows);
        model.addAttribute("actionCount", actionCount);
        model.addAttribute("actionOnly", actionOnly);
        model.addAttribute("today", today);
        model.addAttribute("user", current);
        return "leave-management";
    }

    @GetMapping("/leave-management/user")
    public String detail(@RequestParam("id") Long userId, HttpSession session, Model model) {
        User current = (User) session.getAttribute("user");
        if (!canUse(current)) {
            return "redirect:/home?error=permission";
        }
        User target = userRepository.findById(userId).orElse(null);
        if (target == null) {
            return "redirect:/leave-management";
        }
        LocalDate today = leaveGrantService.today();
        model.addAttribute("target", target);
        model.addAttribute("balance", paidLeaveService.getBalance(target, today));
        model.addAttribute("grantHistory", leaveGrantService.grantHistory(target));
        model.addAttribute("nextGrant", LeaveGrantPolicy.nextSchedule(target, today));
        model.addAttribute("obligation", leaveGrantService.currentObligation(target));
        model.addAttribute("proportional", LeaveGrantPolicy.isProportional(target));
        model.addAttribute("today", today);
        model.addAttribute("user", current);
        return "leave-management-user";
    }

    @PostMapping("/leave-management/grant")
    public String addGrant(@RequestParam("userId") Long userId,
                           @RequestParam(name = "grantDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate grantDate,
                           @RequestParam(name = "days", required = false) BigDecimal days,
                           @RequestParam(name = "note", required = false) String note,
                           HttpSession session, RedirectAttributes redirectAttributes) {
        if (!canUse((User) session.getAttribute("user"))) {
            return "redirect:/home?error=permission";
        }
        try {
            leaveGrantService.addManualGrant(userId, grantDate, days, note);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("leave.grant.added"));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/leave-management/user?id=" + userId;
    }

    @PostMapping("/leave-management/grant/{id}/delete")
    public String deleteGrant(@PathVariable("id") Long grantId, @RequestParam("userId") Long userId,
                              HttpSession session, RedirectAttributes redirectAttributes) {
        if (!canUse((User) session.getAttribute("user"))) {
            return "redirect:/home?error=permission";
        }
        try {
            leaveGrantService.deleteGrant(grantId);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("leave.grant.deleted"));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/leave-management/user?id=" + userId;
    }

    /** 自動付与を今すぐ実行する（入社日を登録・変更した直後など） */
    @PostMapping("/leave-management/run")
    public String runAutoGrant(HttpSession session, RedirectAttributes redirectAttributes) {
        if (!canUse((User) session.getAttribute("user"))) {
            return "redirect:/home?error=permission";
        }
        int created = leaveGrantService.autoGrantAll();
        redirectAttributes.addFlashAttribute("successMessage", messages.get("leave.grant.autoRun", created));
        return "redirect:/leave-management";
    }
}
