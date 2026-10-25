package com.wkrepo.system.service;

import com.wkrepo.system.entity.User;
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

@Service
public class ApplicationNotificationService {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationNotificationService.class);

    private final UserRepository userRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String fromAddress;

    public ApplicationNotificationService(
            UserRepository userRepository,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.notifications.from:}") String fromAddress) {
        this.userRepository = userRepository;
        this.mailSenderProvider = mailSenderProvider;
        this.fromAddress = fromAddress;
    }

    public Result notifyManager(User applicant, String subject, String details) {
        if (!"GENERAL".equals(applicant.getRole())) {
            return Result.NOT_APPLICABLE;
        }
        if (applicant.getManagerId() == null) {
            return Result.NO_MANAGER;
        }

        User manager;
        try {
            manager = userRepository.findById(applicant.getManagerId()).orElse(null);
        } catch (DataAccessException exception) {
            logger.error("Could not load manager for applicant {}", applicant.getId(), exception);
            return Result.FAILED;
        }
        if (manager == null) {
            logger.warn("Manager {} was not found for applicant {}", applicant.getManagerId(), applicant.getId());
            return Result.NO_MANAGER;
        }
        if (manager.getEmail() == null || manager.getEmail().isBlank()) {
            return Result.NO_MANAGER_EMAIL;
        }

        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            logger.warn("SMTP is not configured; application notification for applicant {} was not sent",
                    applicant.getId());
            return Result.SMTP_NOT_CONFIGURED;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        if (fromAddress != null && !fromAddress.isBlank()) {
            message.setFrom(fromAddress);
        }
        message.setTo(manager.getEmail());
        message.setSubject(subject);
        message.setText("上司各位\n\n"
                + applicant.getName() + " さんから申請が提出されました。\n"
                + details + "\n\nシステムにログインして内容をご確認ください。");
        try {
            mailSender.send(message);
            logger.info("Sent application notification to manager {} for applicant {}",
                    manager.getId(), applicant.getId());
            return Result.SENT;
        } catch (MailException exception) {
            logger.error("Failed to send application notification to manager {} for applicant {}",
                    manager.getId(), applicant.getId(), exception);
            return Result.FAILED;
        }
    }

    public enum Result {
        SENT,
        NO_MANAGER,
        NO_MANAGER_EMAIL,
        SMTP_NOT_CONFIGURED,
        FAILED,
        NOT_APPLICABLE
    }
}
