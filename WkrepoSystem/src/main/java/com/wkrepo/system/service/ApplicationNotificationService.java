package com.wkrepo.system.service;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class ApplicationNotificationService {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationNotificationService.class);

    private final UserRepository userRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;
    private final ApprovalRouteService approvalRouteService;

    public ApplicationNotificationService(
            UserRepository userRepository,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.notifications.from:}") String fromAddress,
            ApprovalRouteService approvalRouteService) {
        this.userRepository = userRepository;
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
        this.approvalRouteService = approvalRouteService;
    }

    /**
     * 申請を承認者にメールで通知する。宛先は承認経路（直属の上司と、今日有効な代理承認者）。
     * 上司が設定されていない ADMIN の申請は通知しない。
     */
    public Result notifyManager(User applicant, String subject, String details) {
        if ("ADMIN".equals(applicant.getRole()) && applicant.getManagerId() == null) {
            return Result.NOT_APPLICABLE;
        }
        if (applicant.getManagerId() == null) {
            return Result.NO_MANAGER;
        }

        List<User> approvers;
        try {
            approvers = approvalRouteService.notificationTargets(applicant);
        } catch (DataAccessException exception) {
            logger.error("申請者 {} の承認者を取得できませんでした", applicant.getId(), exception);
            return Result.FAILED;
        }
        if (approvers.isEmpty()) {
            logger.warn("申請者 {} の上司 {} が見つからないか停止中です", applicant.getId(), applicant.getManagerId());
            return Result.NO_MANAGER;
        }
        List<User> withEmail = approvers.stream()
                .filter(approver -> approver.getEmail() != null && !approver.getEmail().isBlank())
                .toList();
        if (withEmail.isEmpty()) {
            return Result.NO_MANAGER_EMAIL;
        }

        String text = "上司各位\n\n"
                + applicant.getName() + " さんから申請が提出されました。\n"
                + details + "\n\nシステムにログインして内容をご確認ください。";
        Result result = Result.FAILED;
        for (User approver : withEmail) {
            Result sent = send(approver.getEmail(), subject, text);
            if (sent == Result.SENT) {
                logger.info("申請者 {} の申請を承認者 {} にメールで通知しました", applicant.getId(), approver.getId());
                result = Result.SENT;
            } else if (sent == Result.SMTP_NOT_CONFIGURED) {
                logger.warn("SMTP が未設定のため、申請者 {} の申請通知を送信しませんでした", applicant.getId());
                return Result.SMTP_NOT_CONFIGURED;
            }
        }
        return result;
    }

    /**
     * 承認・拒否の結果を申請者にメールで通知する。
     * 申請者本人が操作した場合、メールアドレス未登録、SMTP 未設定の場合は送信しない（例外は投げない）。
     *
     * @param applicant  申請者（DB から最新を取得し直す）
     * @param summary    申請内容の要約
     * @param nextStatus 変更後の状態
     * @param actor      承認者
     * @param comment    拒否理由など（任意）
     */
    public Result notifyApplicant(User applicant, String summary, ApplicationStatus nextStatus, User actor,
                                  String comment) {
        return notifyApplicant(applicant, summary, nextStatus, actor, comment, false);
    }

    /**
     * @param reopened 確定済みの月報の差し戻し（結果の表記を「確定取消（差し戻し）」にする）
     */
    public Result notifyApplicant(User applicant, String summary, ApplicationStatus nextStatus, User actor,
                                  String comment, boolean reopened) {
        if (applicant == null || applicant.getId() == null
                || (actor != null && Objects.equals(applicant.getId(), actor.getId()))) {
            return Result.NOT_APPLICABLE;
        }
        String email;
        try {
            email = userRepository.findById(applicant.getId()).map(User::getEmail).orElse(null);
        } catch (DataAccessException exception) {
            logger.error("申請者 {} のメールアドレスを取得できませんでした", applicant.getId(), exception);
            return Result.FAILED;
        }
        if (email == null || email.isBlank()) {
            return Result.NO_APPLICANT_EMAIL;
        }

        boolean rejected = nextStatus == ApplicationStatus.REJECTED;
        String resultLabel = reopened ? "確定取消（差し戻し）" : switch (nextStatus) {
            case REJECTED -> "拒否";
            case MANAGER_APPROVED -> "上司承認（総務の最終承認待ち）";
            case FINALIZED -> "最終承認";
            default -> "承認";
        };
        String actorName = actor == null ? "-" : (actor.getName() == null ? actor.getUsername() : actor.getName());
        StringBuilder text = new StringBuilder()
                .append(applicant.getName()).append(" さん\n\n")
                .append("申請が").append(resultLabel).append("されました。\n\n")
                .append("申請内容：").append(summary).append('\n')
                .append("結果：").append(resultLabel).append('\n')
                .append("承認者：").append(actorName).append('\n');
        if (comment != null && !comment.isBlank()) {
            text.append(reopened ? "差し戻しの理由：" : (rejected ? "拒否理由：" : "コメント："))
                    .append(comment.strip()).append('\n');
        }
        text.append(rejected
                ? "\n内容を確認し、必要に応じて修正して再申請してください。"
                : "\nシステムにログインして内容をご確認ください。");

        Result result = send(email, "【申請結果】" + summary + "：" + resultLabel, text.toString());
        if (result == Result.SENT) {
            logger.info("申請者 {} に申請結果（{}）をメールで通知しました", applicant.getId(), nextStatus);
        } else if (result == Result.SMTP_NOT_CONFIGURED) {
            logger.warn("SMTP が未設定のため、申請者 {} への申請結果の通知を送信しませんでした", applicant.getId());
        }
        return result;
    }

    private Result send(String to, String subject, String text) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            return Result.SMTP_NOT_CONFIGURED;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);
        try {
            mailSender.send(message);
            return Result.SENT;
        } catch (MailException exception) {
            logger.error("通知メールの送信に失敗しました（件名：{}）", subject, exception);
            return Result.FAILED;
        }
    }

    public enum Result {
        SENT,
        NO_MANAGER,
        NO_MANAGER_EMAIL,
        NO_APPLICANT_EMAIL,
        SMTP_NOT_CONFIGURED,
        FAILED,
        NOT_APPLICABLE
    }
}
