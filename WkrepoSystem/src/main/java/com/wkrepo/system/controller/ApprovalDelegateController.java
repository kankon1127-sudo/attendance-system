package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.ApprovalRouteService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

/**
 * 代理承認者の設定（部長・管理者のみ）。不在の期間、指定した部長・管理者が代わりに部下の申請を承認できる。
 */
@Controller
public class ApprovalDelegateController {

    private final ApprovalRouteService approvalRouteService;
    private final UserRepository userRepository;
    private final Messages messages;

    public ApprovalDelegateController(ApprovalRouteService approvalRouteService, UserRepository userRepository,
                                      Messages messages) {
        this.approvalRouteService = approvalRouteService;
        this.userRepository = userRepository;
        this.messages = messages;
    }

    @GetMapping("/approval-delegate")
    public String show(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (!ApprovalRouteService.canBeApprover(user)) {
            return "redirect:/home?error=permission";
        }
        User current = userRepository.findById(user.getId()).orElse(user);
        User delegate = current.getApprovalDelegateId() == null ? null
                : userRepository.findById(current.getApprovalDelegateId()).orElse(null);
        // 自分を代理承認者に指定している上司（今日有効かどうかに関係なく表示）
        List<User> delegators = userRepository.findByApprovalDelegateId(user.getId());

        model.addAttribute("current", current);
        model.addAttribute("delegate", delegate);
        model.addAttribute("delegateActive", current.isApprovalDelegateActiveOn(LocalDate.now()));
        model.addAttribute("candidates", approvalRouteService.delegateCandidates(current));
        model.addAttribute("delegators", delegators);
        model.addAttribute("today", LocalDate.now());
        model.addAttribute("user", user);
        return "approval-delegate";
    }

    @PostMapping("/approval-delegate")
    public String save(@RequestParam(name = "delegateId", required = false) Long delegateId,
                       @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       HttpSession session, RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (!ApprovalRouteService.canBeApprover(user)) {
            return "redirect:/home?error=permission";
        }
        try {
            approvalRouteService.setDelegate(user, delegateId, from, to);
            redirectAttributes.addFlashAttribute("successMessage",
                    messages.get(delegateId == null ? "delegate.cleared" : "delegate.saved"));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/approval-delegate";
    }

    @PostMapping("/approval-delegate/clear")
    public String clear(HttpSession session, RedirectAttributes redirectAttributes) {
        return save(null, null, null, session, redirectAttributes);
    }
}
