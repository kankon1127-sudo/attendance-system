package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.dto.ApplicationDetailDTO;
import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.service.ApplicationListService;
import com.wkrepo.system.service.ApplicationNotificationService;
import com.wkrepo.system.service.ApprovalHistoryService;
import com.wkrepo.system.service.MonthlyReportService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/application")
public class ApplicationListController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationListController.class);
    private static final int PAGE_SIZE = 20;

    @Autowired
    private ApplicationListService applicationListService;

    @Autowired
    private MonthlyReportService monthlyReportService;

    @Autowired
    private ApprovalHistoryService approvalHistoryService;

    @Autowired
    private Messages messages;

    @Autowired
    private ApplicationNotificationService applicationNotificationService;

    @GetMapping("/list")
    public String getApplicationList(HttpSession session, Model model,
                                     @RequestParam(name = "filterType", required = false) String filterType,
                                     @RequestParam(name = "filterStatus", required = false) String filterStatus,
                                     @RequestParam(name = "page", defaultValue = "1") int page) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        List<ApplicationItem> applications = applicationListService.getApplicationsForUser(user, filterType, filterStatus);

        // 4 種類の申請をまとめて申請日順に並べるため、メモリ上でページングする
        int totalCount = applications.size();
        int totalPages = Math.max(1, (totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
        int currentPageNumber = Math.min(Math.max(page, 1), totalPages);
        int fromIndex = (currentPageNumber - 1) * PAGE_SIZE;
        int toIndex = Math.min(fromIndex + PAGE_SIZE, totalCount);

        List<ApplicationItem> pageItems = applications.subList(fromIndex, toIndex);
        approvalHistoryService.fillRejectReasons(pageItems);
        model.addAttribute("applications", pageItems);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("page", currentPageNumber);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageOffset", fromIndex);
        // ページ番号ボタン：現在ページの前後 2 ページずつ
        model.addAttribute("pageWindowStart", Math.max(1, currentPageNumber - 2));
        model.addAttribute("pageWindowEnd", Math.min(totalPages, currentPageNumber + 2));
        model.addAttribute("filterType", filterType);
        model.addAttribute("filterStatus", filterStatus);
        model.addAttribute("allStatuses", com.wkrepo.system.enums.ApplicationStatus.values());
        model.addAttribute("currentPage", "application-list");
        model.addAttribute("user", user);

        return "application-list";
    }

    @PostMapping("/approve")
    public String approveApplication(@RequestParam("id") Long id,
                                   @RequestParam("type") String type,
                                   HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!applicationListService.canApproveApplication(id, type, user)) {
            log.warn("[ApplicationListController] 権限なしの承認要求 id={}, type={}, userId={}",
                    id, type, user.getId());
            return "redirect:/application/list";
        }

        ApplicationStatus nextStatus = approvedStatus(type, id, user);
        notifyApplicant(approvalHistoryService.transition(type, id, nextStatus, user), nextStatus, user, null);

        return "redirect:/application/list";
    }

    /**
     * 一括承認。keys は「種類:ID」（例：PAID_LEAVE:12）。1 件ずつ権限を確認し、承認できないものは飛ばす。
     * 1 件の失敗で残りを止めないよう、件ごとに処理する（各件は transition のトランザクション内）。
     */
    @PostMapping("/approve-bulk")
    public String approveBulk(@RequestParam(name = "keys", required = false) List<String> keys,
                              @RequestParam(name = "filterType", required = false) String filterType,
                              @RequestParam(name = "filterStatus", required = false) String filterStatus,
                              HttpSession session, RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        int approved = 0;
        int skipped = 0;
        for (String key : keys == null ? List.<String>of() : keys) {
            String[] parts = key == null ? new String[0] : key.split(":", 2);
            Long id = parts.length == 2 ? parseId(parts[1]) : null;
            String type = parts.length == 2 ? parts[0] : null;
            if (id == null || !applicationListService.canApproveApplication(id, type, user)) {
                skipped++;
                continue;
            }
            try {
                ApplicationStatus nextStatus = approvedStatus(type, id, user);
                notifyApplicant(approvalHistoryService.transition(type, id, nextStatus, user), nextStatus, user, null);
                approved++;
            } catch (RuntimeException e) {
                log.error("[ApplicationListController] 一括承認でエラー id={}, type={}, userId={}", id, type, user.getId(), e);
                skipped++;
            }
        }
        if (approved > 0) {
            redirectAttributes.addFlashAttribute("bulkMessage", messages.get("application.bulkApproved", approved));
        }
        if (skipped > 0) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("application.bulkSkipped", skipped));
        }
        // 絞り込み条件を保ったまま一覧に戻る（値は英大文字と _ のみ受け付ける）
        StringBuilder redirect = new StringBuilder("redirect:/application/list");
        String separator = "?";
        if (filterType != null && filterType.matches("[A-Z_]{1,30}")) {
            redirect.append(separator).append("filterType=").append(filterType);
            separator = "&";
        }
        if (filterStatus != null && filterStatus.matches("[A-Z_]{1,30}")) {
            redirect.append(separator).append("filterStatus=").append(filterStatus);
        }
        return redirect.toString();
    }

    private static Long parseId(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @PostMapping("/reject")
    public String rejectApplication(@RequestParam("id") Long id,
                                  @RequestParam("type") String type,
                                  @RequestParam(name = "reason", required = false) String reason,
                                  HttpSession session, RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!applicationListService.canApproveApplication(id, type, user)) {
            log.warn("[ApplicationListController] 権限なしの拒否要求 id={}, type={}, userId={}",
                    id, type, user.getId());
            return "redirect:/application/list";
        }

        try {
            ApprovalHistoryService.Transition transition =
                    approvalHistoryService.transition(type, id, ApplicationStatus.REJECTED, user, reason);
            notifyApplicant(transition, ApplicationStatus.REJECTED, user, reason);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }

        return "redirect:/application/list";
    }

    /** 確定済みの月報の差し戻し（総務・管理者、理由必須）。当月のロックが外れ、本人が修正して再提出できる */
    @PostMapping("/reopen")
    public String reopenMonthlyReport(@RequestParam("id") Long id,
                                      @RequestParam(name = "reason", required = false) String reason,
                                      HttpSession session, RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        try {
            ApprovalHistoryService.Transition transition = monthlyReportService.reopenReport(id, user, reason);
            try {
                applicationNotificationService.notifyApplicant(transition.applicant(), transition.summary(),
                        ApplicationStatus.REJECTED, user, reason, true);
            } catch (RuntimeException e) {
                log.error("[ApplicationListController] 差し戻しの通知でエラー（差し戻しは完了済み） id={}", id, e);
            }
            redirectAttributes.addFlashAttribute("successMessage", "monthly.reopened");
        } catch (SecurityException e) {
            log.warn("[ApplicationListController] 権限なしの差し戻し要求 id={}, userId={}", id, user.getId());
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/application/list";
    }

    @PostMapping("/withdraw")
    public String withdrawMonthlyReport(@RequestParam("id") Long id, HttpSession session,
                                        RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        try {
            MonthlyReport report = monthlyReportService.withdrawReport(id, user);
            redirectAttributes.addFlashAttribute("successMessage", "monthly.withdrawSuccess");
            return "redirect:/monthly-summary?year=" + report.getYear() + "&month=" + report.getMonth();
        } catch (SecurityException e) {
            log.warn("[ApplicationListController] 権限なしの月報撤回要求 id={}, userId={}", id, user.getId(), e);
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/application/list";
    }

    @GetMapping("/api/detail")
    @ResponseBody
    public ResponseEntity<?> getApplicationDetailApi(@RequestParam("id") Long id,
                                                      @RequestParam("type") String type,
                                                      HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("common.error.loginRequired"));
            return ResponseEntity.status(401).body(err);
        }
        try {
            ApplicationDetailDTO detail = applicationListService.getApplicationDetail(id, type, user);
            detail.setApprovalHistory(approvalHistoryService.getHistory(type, id));
            return ResponseEntity.ok(detail);
        } catch (SecurityException e) {
            log.warn("[ApplicationListController] 権限なし id={}, type={}, userId={}", id, type, user.getId(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("common.error.forbidden"));
            return ResponseEntity.status(403).body(err);
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.of(e));
            return ResponseEntity.status(400).body(err);
        } catch (Exception e) {
            log.error("[ApplicationListController] 詳細取得エラー id={}, type={}", id, type, e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("application.error.loadFailed"));
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/api/approve")
    @ResponseBody
    public ResponseEntity<?> approveApplicationApi(@RequestParam("id") Long id,
                                                    @RequestParam("type") String type,
                                                    HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("common.error.loginRequired"));
            return ResponseEntity.status(401).body(err);
        }
        if (!applicationListService.canApproveApplication(id, type, user)) {
            log.warn("[ApplicationListController] 権限なしのAPI承認要求 id={}, type={}, userId={}",
                    id, type, user.getId());
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("application.error.cannotApprove"));
            return ResponseEntity.status(403).body(err);
        }
        try {
            ApplicationStatus nextStatus = approvedStatus(type, id, user);
            notifyApplicant(approvalHistoryService.transition(type, id, nextStatus, user), nextStatus, user, null);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", messages.get("application.approvedMessage"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[ApplicationListController] API承認エラー id={}, type={}, userId={}", id, type, user.getId(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("application.error.approveFailed", messages.of(e)));
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/api/reject")
    @ResponseBody
    public ResponseEntity<?> rejectApplicationApi(@RequestParam("id") Long id,
                                                   @RequestParam("type") String type,
                                                   @RequestParam(name = "reason", required = false) String reason,
                                                   HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("common.error.loginRequired"));
            return ResponseEntity.status(401).body(err);
        }
        if (!applicationListService.canApproveApplication(id, type, user)) {
            log.warn("[ApplicationListController] 権限なしのAPI拒否要求 id={}, type={}, userId={}",
                    id, type, user.getId());
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("application.error.cannotReject"));
            return ResponseEntity.status(403).body(err);
        }
        try {
            ApprovalHistoryService.Transition transition =
                    approvalHistoryService.transition(type, id, ApplicationStatus.REJECTED, user, reason);
            notifyApplicant(transition, ApplicationStatus.REJECTED, user, reason);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", messages.get("application.rejectedMessage"));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.of(e));
            return ResponseEntity.badRequest().body(err);
        } catch (Exception e) {
            log.error("[ApplicationListController] API拒否エラー id={}, type={}, userId={}", id, type, user.getId(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", messages.get("application.error.rejectFailed", messages.of(e)));
            return ResponseEntity.status(500).body(err);
        }
    }

    /** 承認・拒否の結果を申請者にメールで通知する。通知の失敗で承認・拒否自体は失敗させない */
    private void notifyApplicant(ApprovalHistoryService.Transition transition, ApplicationStatus nextStatus,
                                 User actor, String comment) {
        try {
            applicationNotificationService.notifyApplicant(
                    transition.applicant(), transition.summary(), nextStatus, actor, comment);
        } catch (RuntimeException e) {
            log.error("[ApplicationListController] 申請結果の通知でエラー（承認・拒否は完了済み） status={}", nextStatus, e);
        }
    }

    private ApplicationStatus approvedStatus(String type, Long id, User user) {
        if ("MONTHLY_REPORT".equals(type)) {
            MonthlyReport report = monthlyReportService.findById(id);
            if (report != null && ApplicationStatus.MANAGER_APPROVED.equals(report.getStatus())
                    && ("SOUMU".equals(user.getRole()) || "ADMIN".equals(user.getRole()))) {
                return ApplicationStatus.FINALIZED;
            }
            return ApplicationStatus.MANAGER_APPROVED;
        }
        return ApplicationStatus.APPROVED;
    }
}
