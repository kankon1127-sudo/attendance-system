package com.wkrepo.system.controller;

import com.wkrepo.system.dto.ApplicationDetailDTO;
import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.service.ApplicationListService;
import com.wkrepo.system.service.GeneralExpenseService;
import com.wkrepo.system.service.MonthlyReportService;
import com.wkrepo.system.service.PaidLeaveService;
import com.wkrepo.system.service.TravelExpenseService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/application")
public class ApplicationListController {

    private static final Logger log = LoggerFactory.getLogger(ApplicationListController.class);

    @Autowired
    private ApplicationListService applicationListService;

    @Autowired
    private TravelExpenseService travelExpenseService;

    @Autowired
    private GeneralExpenseService generalExpenseService;

    @Autowired
    private PaidLeaveService paidLeaveService;

    @Autowired
    private MonthlyReportService monthlyReportService;

    @GetMapping("/list")
    public String getApplicationList(HttpSession session, Model model,
                                     @RequestParam(name = "filterType", required = false) String filterType,
                                     @RequestParam(name = "filterStatus", required = false) String filterStatus) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        List<ApplicationItem> applications = applicationListService.getApplicationsForUser(user, filterType, filterStatus);

        model.addAttribute("applications", applications);
        model.addAttribute("filterType", filterType);
        model.addAttribute("filterStatus", filterStatus);
        model.addAttribute("allStatuses", com.wkrepo.system.enums.ApplicationStatus.values());
        model.addAttribute("currentPage", "application-list");
        model.addAttribute("pageTitle", "申請一覧");
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

        switch (type) {
            case "TRAVEL":
                travelExpenseService.updateStatus(id, ApplicationStatus.APPROVED);
                break;
            case "GENERAL":
                generalExpenseService.updateStatus(id, ApplicationStatus.APPROVED);
                break;
            case "PAID_LEAVE":
                paidLeaveService.updateStatus(id, ApplicationStatus.APPROVED);
                break;
            case "MONTHLY_REPORT":
                MonthlyReport report = monthlyReportService.findById(id);
                if (report != null && ApplicationStatus.MANAGER_APPROVED.equals(report.getStatus())
                        && ("SOUMU".equals(user.getRole()) || "ADMIN".equals(user.getRole()))) {
                    monthlyReportService.updateStatus(id, ApplicationStatus.FINALIZED);
                } else {
                    monthlyReportService.updateStatus(id, ApplicationStatus.MANAGER_APPROVED);
                }
                break;
        }

        return "redirect:/application/list";
    }

    @PostMapping("/reject")
    public String rejectApplication(@RequestParam("id") Long id,
                                  @RequestParam("type") String type,
                                  HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!applicationListService.canApproveApplication(id, type, user)) {
            log.warn("[ApplicationListController] 権限なしの拒否要求 id={}, type={}, userId={}",
                    id, type, user.getId());
            return "redirect:/application/list";
        }

        switch (type) {
            case "TRAVEL":
                travelExpenseService.updateStatus(id, ApplicationStatus.REJECTED);
                break;
            case "GENERAL":
                generalExpenseService.updateStatus(id, ApplicationStatus.REJECTED);
                break;
            case "PAID_LEAVE":
                paidLeaveService.updateStatus(id, ApplicationStatus.REJECTED);
                break;
            case "MONTHLY_REPORT":
                monthlyReportService.updateStatus(id, ApplicationStatus.REJECTED);
                break;
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
            err.put("message", "未ログイン");
            return ResponseEntity.status(401).body(err);
        }
        try {
            ApplicationDetailDTO detail = applicationListService.getApplicationDetail(id, type, user);
            return ResponseEntity.ok(detail);
        } catch (SecurityException e) {
            log.warn("[ApplicationListController] 権限なし id={}, type={}, userId={}", id, type, user.getId(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "権限がありません");
            return ResponseEntity.status(403).body(err);
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", e.getMessage());
            return ResponseEntity.status(400).body(err);
        } catch (Exception e) {
            log.error("[ApplicationListController] 詳細取得エラー id={}, type={}", id, type, e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "データ取得に失敗しました");
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
            err.put("message", "未ログイン");
            return ResponseEntity.status(401).body(err);
        }
        if (!applicationListService.canApproveApplication(id, type, user)) {
            log.warn("[ApplicationListController] 権限なしのAPI承認要求 id={}, type={}, userId={}",
                    id, type, user.getId());
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "権限がないか、申請が承認可能な状態ではありません");
            return ResponseEntity.status(403).body(err);
        }
        try {
            switch (type) {
                case "TRAVEL":
                    travelExpenseService.updateStatus(id, ApplicationStatus.APPROVED);
                    break;
                case "GENERAL":
                    generalExpenseService.updateStatus(id, ApplicationStatus.APPROVED);
                    break;
                case "PAID_LEAVE":
                    paidLeaveService.updateStatus(id, ApplicationStatus.APPROVED);
                    break;
                case "MONTHLY_REPORT":
                    MonthlyReport report = monthlyReportService.findById(id);
                    if (report != null && ApplicationStatus.MANAGER_APPROVED.equals(report.getStatus())
                            && ("SOUMU".equals(user.getRole()) || "ADMIN".equals(user.getRole()))) {
                        monthlyReportService.updateStatus(id, ApplicationStatus.FINALIZED);
                    } else {
                        monthlyReportService.updateStatus(id, ApplicationStatus.MANAGER_APPROVED);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown type: " + type);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "承認しました");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[ApplicationListController] API承認エラー id={}, type={}, userId={}", id, type, user.getId(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "承認に失敗しました: " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    @PostMapping("/api/reject")
    @ResponseBody
    public ResponseEntity<?> rejectApplicationApi(@RequestParam("id") Long id,
                                                   @RequestParam("type") String type,
                                                   HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "未ログイン");
            return ResponseEntity.status(401).body(err);
        }
        if (!applicationListService.canApproveApplication(id, type, user)) {
            log.warn("[ApplicationListController] 権限なしのAPI拒否要求 id={}, type={}, userId={}",
                    id, type, user.getId());
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "権限がないか、申請が拒否可能な状態ではありません");
            return ResponseEntity.status(403).body(err);
        }
        try {
            switch (type) {
                case "TRAVEL":
                    travelExpenseService.updateStatus(id, ApplicationStatus.REJECTED);
                    break;
                case "GENERAL":
                    generalExpenseService.updateStatus(id, ApplicationStatus.REJECTED);
                    break;
                case "PAID_LEAVE":
                    paidLeaveService.updateStatus(id, ApplicationStatus.REJECTED);
                    break;
                case "MONTHLY_REPORT":
                    monthlyReportService.updateStatus(id, ApplicationStatus.REJECTED);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown type: " + type);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "拒否しました");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[ApplicationListController] API拒否エラー id={}, type={}, userId={}", id, type, user.getId(), e);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "拒否に失敗しました: " + e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }
}
