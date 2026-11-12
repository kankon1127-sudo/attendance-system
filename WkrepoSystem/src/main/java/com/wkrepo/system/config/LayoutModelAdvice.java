package com.wkrepo.system.config;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * すべての画面（layout.html）で使う値。ヘッダーのベルに表示する未読通知の件数。
 * JSON を返す API でも呼ばれるが、件数を数えるだけの軽いクエリ。
 */
@ControllerAdvice
public class LayoutModelAdvice {

    private final NotificationService notificationService;

    public LayoutModelAdvice(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 勤務区分の保存値 → 画面の文言キー（勤怠・チーム・月報の画面で使う） */
    @ModelAttribute("statusLabels")
    public java.util.Map<String, String> statusLabels() {
        return com.wkrepo.system.service.AttendanceService.STATUS_LABEL_KEYS;
    }

    @ModelAttribute("unreadNotificationCount")
    public long unreadNotificationCount(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute("user");
        return user instanceof User loggedIn ? notificationService.unreadCount(loggedIn.getId()) : 0;
    }
}
