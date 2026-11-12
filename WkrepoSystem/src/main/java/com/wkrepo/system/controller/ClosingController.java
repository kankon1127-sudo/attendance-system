package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.ReminderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 締め状況（総務・管理者）：指定月の月報の提出状況を全員分表示し、未提出者にリマインドを送る。
 */
@Controller
public class ClosingController {

    /** 表示順：未作成・下書き・拒否（要対応）→ 提出済 → 上長承認済 → 確定 */
    private static final List<String> STATUS_ORDER = List.of(
            "NONE", "DRAFT", "REJECTED", "SUBMITTED", "MANAGER_APPROVED", "FINALIZED");

    private final UserRepository userRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final ReminderService reminderService;
    private final Messages messages;

    public ClosingController(UserRepository userRepository, MonthlyReportRepository monthlyReportRepository,
                             ReminderService reminderService, Messages messages) {
        this.userRepository = userRepository;
        this.monthlyReportRepository = monthlyReportRepository;
        this.reminderService = reminderService;
        this.messages = messages;
    }

    public static boolean canUse(User user) {
        return user != null && ("SOUMU".equals(user.getRole()) || "ADMIN".equals(user.getRole()));
    }

    @GetMapping("/closing")
    public String closing(@RequestParam(name = "year", required = false) Integer year,
                          @RequestParam(name = "month", required = false) Integer month,
                          HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (!canUse(user)) {
            return "redirect:/home?error=permission";
        }
        YearMonth target = targetMonth(year, month);
        List<Row> rows = new ArrayList<>();
        for (User member : userRepository.findAll()) {
            if (!ReminderService.mustSubmitMonthlyReport(member)) {
                continue;
            }
            MonthlyReport report = monthlyReportRepository
                    .findByUserIdAndYearAndMonth(member.getId(), target.getYear(), target.getMonthValue())
                    .orElse(null);
            String status = report == null || report.getStatus() == null ? "NONE" : report.getStatus().name();
            rows.add(new Row(member, status, report == null ? null : report.getId()));
        }
        rows.sort(Comparator.comparingInt((Row row) -> STATUS_ORDER.indexOf(row.status()))
                .thenComparing(row -> row.member().getEmployeeId(), Comparator.nullsLast(String::compareTo)));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : STATUS_ORDER) {
            counts.put(status, rows.stream().filter(row -> row.status().equals(status)).count());
        }
        long unsubmitted = counts.get("NONE") + counts.get("DRAFT") + counts.get("REJECTED");

        YearMonth prev = target.minusMonths(1);
        YearMonth next = target.plusMonths(1);
        model.addAttribute("year", target.getYear());
        model.addAttribute("month", target.getMonthValue());
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());
        model.addAttribute("hasNext", !next.isAfter(YearMonth.now()));
        model.addAttribute("rows", rows);
        model.addAttribute("counts", counts);
        model.addAttribute("total", rows.size());
        model.addAttribute("unsubmitted", unsubmitted);
        model.addAttribute("user", user);
        return "closing";
    }

    @PostMapping("/closing/remind")
    public String remind(@RequestParam("year") int year, @RequestParam("month") int month,
                         HttpSession session, RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (!canUse(user)) {
            return "redirect:/home?error=permission";
        }
        YearMonth target = targetMonth(year, month);
        int sent = reminderService.remindUnsubmittedNow(target);
        redirectAttributes.addFlashAttribute("successMessage", messages.get("closing.reminded", sent));
        return "redirect:/closing?year=" + target.getYear() + "&month=" + target.getMonthValue();
    }

    private YearMonth targetMonth(Integer year, Integer month) {
        YearMonth current = YearMonth.now();
        if (year == null || month == null || month < 1 || month > 12) {
            return current.minusMonths(1); // 既定は前月（締めの対象）
        }
        YearMonth requested = YearMonth.of(year, month);
        return requested.isAfter(current) ? current : requested;
    }

    /** 1 行：社員と月報の状態（NONE = 未作成） */
    public record Row(User member, String status, Long reportId) {
        public boolean needsAction() {
            return "NONE".equals(status) || "DRAFT".equals(status) || "REJECTED".equals(status);
        }
    }
}
