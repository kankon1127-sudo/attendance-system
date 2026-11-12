package com.wkrepo.system.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wkrepo.system.entity.Notification;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * システム内通知（画面右上のベル）とメール。
 * 通知はメッセージキーと引数で保存し、表示時に画面の言語で組み立てる。メールは日本語で送る。
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Locale MAIL_LOCALE = Locale.JAPANESE;

    private final NotificationRepository notificationRepository;
    private final MessageSource messageSource;
    private final ApplicationNotificationService mailService;
    private final String baseUrl;

    public NotificationService(NotificationRepository notificationRepository, MessageSource messageSource,
                               ApplicationNotificationService mailService,
                               @Value("${app.base-url:}") String baseUrl) {
        this.notificationRepository = notificationRepository;
        this.messageSource = messageSource;
        this.mailService = mailService;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    /**
     * 通知を作成し、メールアドレスがあればメールも送る。同じ dedupeKey の通知が既にあれば何もしない。
     *
     * @return 作成した場合 true（重複で作らなかった場合 false）
     */
    // @Transactional は付けない：一意制約違反で外側のトランザクションがロールバック専用になるのを避ける
    public boolean notify(User recipient, String type, String messageKey, List<String> args, String link,
                          String dedupeKey) {
        if (recipient == null || recipient.getId() == null || "INACTIVE".equals(recipient.getStatus())) {
            return false;
        }
        if (dedupeKey != null && notificationRepository.existsByUserIdAndDedupeKey(recipient.getId(), dedupeKey)) {
            return false;
        }
        Notification notification = new Notification();
        notification.setUserId(recipient.getId());
        notification.setType(type);
        notification.setMessageKey(messageKey);
        notification.setArgsJson(toJson(args));
        notification.setLink(link);
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(LocalDateTime.now());
        try {
            notificationRepository.save(notification);
        } catch (DataIntegrityViolationException duplicate) {
            // 同時に同じ通知を作ろうとした場合（一意制約）。既にあるので何もしない
            return false;
        }
        sendMail(recipient, notification);
        return true;
    }

    public long unreadCount(Long userId) {
        return userId == null ? 0 : notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /** 画面表示用：新しい順に最大 100 件、本文は画面の言語で組み立てる */
    public List<View> recent(Long userId, Locale locale) {
        return notificationRepository.findTop100ByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(notification -> new View(notification, render(notification, locale)))
                .toList();
    }

    /** 通知を開く：既読にして遷移先を返す（他人の通知は開けない） */
    @Transactional
    public Optional<String> open(Long id, Long userId) {
        return notificationRepository.findByIdAndUserId(id, userId).map(notification -> {
            if (notification.getReadAt() == null) {
                notification.setReadAt(LocalDateTime.now());
                notificationRepository.save(notification);
            }
            return notification.getLink() == null || notification.getLink().isBlank() ? "/notifications" : notification.getLink();
        });
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId, LocalDateTime.now());
    }

    public String render(Notification notification, Locale locale) {
        return messageSource.getMessage(notification.getMessageKey(), fromJson(notification.getArgsJson()).toArray(),
                notification.getMessageKey(), locale);
    }

    private void sendMail(User recipient, Notification notification) {
        if (recipient.getEmail() == null || recipient.getEmail().isBlank()) {
            return;
        }
        String body = render(notification, MAIL_LOCALE);
        String text = recipient.getName() + " さん\n\n" + body + "\n"
                + (notification.getLink() == null ? "" : "\n" + baseUrl + notification.getLink() + "\n")
                + "\n（このメールは勤怠システムから自動送信されています）";
        try {
            mailService.sendMail(recipient.getEmail(), "【Wkrepo】" + firstLine(body), text);
        } catch (RuntimeException e) {
            logger.error("通知メールの送信でエラー（通知は作成済み） userId={}", recipient.getId(), e);
        }
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }

    private static String toJson(List<String> args) {
        try {
            return JSON.writeValueAsString(args == null ? List.of() : args);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** 画面表示用 */
    public record View(Notification notification, String message) {
    }
}
