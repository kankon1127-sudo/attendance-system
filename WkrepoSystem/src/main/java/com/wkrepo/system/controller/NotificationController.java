package com.wkrepo.system.controller;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Locale;

/** システム内通知（ベル）の一覧・開く・すべて既読（本人の通知のみ） */
@Controller
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/notifications")
    public String list(HttpSession session, Model model, Locale locale) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        model.addAttribute("notifications", notificationService.recent(user.getId(), locale));
        model.addAttribute("user", user);
        return "notifications";
    }

    /** 既読にして遷移先へ（遷移先はアプリ内のパスのみ。他人の通知は開けない） */
    @GetMapping("/notifications/{id}/open")
    public String open(@PathVariable("id") Long id, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        String link = notificationService.open(id, user.getId()).orElse("/notifications");
        return "redirect:" + (link.startsWith("/") && !link.startsWith("//") ? link : "/notifications");
    }

    @PostMapping("/notifications/read-all")
    public String readAll(HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        notificationService.markAllRead(user.getId());
        return "redirect:/notifications";
    }
}
