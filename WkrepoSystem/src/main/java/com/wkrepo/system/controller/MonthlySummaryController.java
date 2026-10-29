package com.wkrepo.system.controller;

import com.wkrepo.system.dto.MonthlySummaryDTO;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.MonthlyReportService;
import com.wkrepo.system.service.MonthlySummaryService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
public class MonthlySummaryController {

    private static final Logger log = LoggerFactory.getLogger(MonthlySummaryController.class);

    @Autowired
    private MonthlySummaryService monthlySummaryService;

    @Autowired
    private MonthlyReportService monthlyReportService;

    @Autowired
    private UserRepository userRepository;

    @GetMapping("/monthly-summary")
    public String showMonthlySummary(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            HttpSession session,
            Model model) {

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        log.info("[月報サマリー] アクセス userId={}, name={}, year={}, month={}",
                user.getId(), user.getName(), year, month);

        LocalDate today = LocalDate.now();
        int currentYear = (year != null) ? year : today.getYear();
        int currentMonth = (month != null) ? month : today.getMonthValue();

        YearMonth yearMonth = YearMonth.of(currentYear, currentMonth);

        model.addAttribute("user", user);
        model.addAttribute("yearMonth", yearMonth);
        model.addAttribute("year", currentYear);
        model.addAttribute("month", currentMonth);
        User directApprover = user.getManagerId() == null
                ? null
                : userRepository.findById(user.getManagerId())
                        .filter(manager -> "MANAGER".equals(manager.getRole())
                                || "ADMIN".equals(manager.getRole()))
                        .orElse(null);
        model.addAttribute("directApprover", directApprover);
        model.addAttribute("selectedApproverId",
                directApprover != null ? directApprover.getId() : null);

        YearMonth prev = yearMonth.minusMonths(1);
        YearMonth next = yearMonth.plusMonths(1);
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());

        int currentStep = 1;
        String workContent = "";

        try {
            log.debug("[月報サマリー] DBから月報を検索 userId={}, year={}, month={}",
                    user.getId(), currentYear, currentMonth);
            Optional<MonthlyReport> reportOpt = monthlyReportService.findReport(user, currentYear, currentMonth);
            if (reportOpt.isPresent()) {
                MonthlyReport report = reportOpt.get();
                currentStep = monthlyReportService.statusToStep(report.getStatus());
                model.addAttribute("selectedApproverId", report.getApproverId());
                if (report.getWorkContent() != null) {
                    workContent = report.getWorkContent();
                }
                log.info("[月報サマリー] DBから月報を取得 reportId={}, status={}, step={}",
                        report.getId(), report.getStatus(), currentStep);
            } else {
                log.info("[月報サマリー] DBに月報なし。Sessionからフォールバック userId={}", user.getId());
                Integer sessionStep = (Integer) session.getAttribute("monthlyStep_" + user.getId() + "_" + currentYear + "_" + currentMonth);
                if (sessionStep != null) {
                    currentStep = sessionStep;
                    log.info("[月報サマリー] Sessionからステップ取得 step={}", currentStep);
                }
            }
        } catch (Exception e) {
            log.error("[月報サマリー] 月報データ取得でエラー userId={}, year={}, month={}",
                    user.getId(), currentYear, currentMonth, e);
            e.printStackTrace();
        }

        model.addAttribute("currentStep", currentStep);
        model.addAttribute("workContent", workContent);
        model.addAttribute("approvalSteps", getApprovalSteps(currentStep));

        String statusText = "下書き";
        if (currentStep == 2) {
            statusText = "提出済";
        } else if (currentStep == 3) {
            statusText = "リーダー承認済";
        } else if (currentStep >= 4) {
            statusText = "総務確認済";
        }
        model.addAttribute("statusText", statusText);
        log.info("[月報サマリー] 表示データ設定完了 currentStep={}, statusText={}", currentStep, statusText);

        try {
            MonthlySummaryDTO summary = monthlySummaryService.calculateMonthlySummary(user, currentYear, currentMonth);
            model.addAttribute("summary", summary);
            log.debug("[月報サマリー] 統計データ計算完了 workDays={}, totalHours={}, totalExpense={}",
                    summary.getWorkDays(), summary.getTotalWorkHours(), summary.getTotalExpense());
        } catch (Exception e) {
            log.error("[月報サマリー] 統計データ計算でエラー", e);
            e.printStackTrace();
            model.addAttribute("summary", new MonthlySummaryDTO());
        }

        return "monthly-summary";
    }

    @PostMapping("/monthly-summary/save-draft")
    public String saveDraft(
            @RequestParam("year") int year,
            @RequestParam("month") int month,
            @RequestParam("workContent") String workContent,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        try {
            log.info("[月報サマリー] 下書き保存開始 userId={}, year={}, month={}, workContentLength={}",
                    user.getId(), year, month, workContent == null ? 0 : workContent.length());
            MonthlyReport saved = monthlyReportService.saveDraft(user, year, month, workContent);
            session.setAttribute("monthlyStep_" + user.getId() + "_" + year + "_" + month, 1);
            log.info("[月報サマリー] 下書き保存成功 reportId={}, status={}", saved.getId(), saved.getStatus());
        } catch (Exception e) {
            log.error("[月報サマリー] 下書き保存失敗 userId={}, year={}, month={}",
                    user.getId(), year, month, e);
            redirectAttributes.addFlashAttribute("errorMessage",
                    e.getMessage() != null ? e.getMessage() : "月報の下書き保存に失敗しました。");
        }
        return "redirect:/monthly-summary?year=" + year + "&month=" + month;
    }

    @PostMapping("/monthly-summary/submit")
    public String submit(
            @RequestParam("year") int year,
            @RequestParam("month") int month,
            @RequestParam("workContent") String workContent,
            @RequestParam(name = "approverId", required = false) Long approverId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        User user = (User) session.getAttribute("user");
        if (user != null) {
            try {
                User directApprover = user.getManagerId() == null
                        ? null
                        : userRepository.findById(user.getManagerId())
                                .filter(manager -> "MANAGER".equals(manager.getRole())
                                        || "ADMIN".equals(manager.getRole()))
                                .orElse(null);
                if (directApprover == null || !directApprover.getId().equals(approverId)) {
                    throw new IllegalArgumentException("直属上司未设置或审批人信息不正确，请联系管理员更新员工信息。");
                }
                log.info("[月報サマリー] 提出開始 userId={}, year={}, month={}, approverId={}, workContentLength={}",
                        user.getId(), year, month, directApprover.getId(),
                        workContent == null ? 0 : workContent.length());
                MonthlyReport saved = monthlyReportService.submitReport(
                        user, year, month, workContent, directApprover.getId());
                session.setAttribute("monthlyStep_" + user.getId() + "_" + year + "_" + month, 2);
                log.info("[月報サマリー] 提出成功 reportId={}, status={}", saved.getId(), saved.getStatus());
            } catch (Exception e) {
                log.error("[月報サマリー] 提出失敗 userId={}, year={}, month={}",
                        user.getId(), year, month, e);
                redirectAttributes.addFlashAttribute("errorMessage",
                        e.getMessage() != null ? e.getMessage() : "月報の提出に失敗しました。");
            }
        }
        return "redirect:/monthly-summary?year=" + year + "&month=" + month;
    }

    private List<Map<String, Object>> getApprovalSteps(int currentStep) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("step", i);
            String name = switch (i) {
                case 1 -> "下書き";
                case 2 -> "提出済";
                case 3 -> "リーダー承認";
                case 4 -> "総務確認";
                default -> "";
            };
            stepMap.put("name", name);
            stepMap.put("active", i == currentStep);
            stepMap.put("completed", i < currentStep);
            steps.add(stepMap);
        }
        return steps;
    }
}
