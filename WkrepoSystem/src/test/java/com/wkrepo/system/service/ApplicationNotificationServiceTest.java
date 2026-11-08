package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 申請結果（承認・拒否）の申請者へのメール通知 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationNotificationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ObjectProvider<JavaMailSender> mailSenderProvider;
    @Mock
    private JavaMailSender mailSender;

    private ApplicationNotificationService service;

    private final User applicant = TestData.user(1L, "GENERAL", "开发部");
    private final User manager = TestData.user(2L, "MANAGER", "开发部");

    @BeforeEach
    void setUp() {
        service = new ApplicationNotificationService(userRepository, mailSenderProvider, "noreply@example.com");
        applicant.setName("山田太郎");
        manager.setName("佐藤部长");
        when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
    }

    @Test
    void rejectionMailContainsReason() {
        withEmail("yamada@example.com");

        ApplicationNotificationService.Result result = service.notifyApplicant(
                applicant, "年休 2026-11-13〜2026-11-16（2.0 日）", ApplicationStatus.REJECTED, manager, "繁忙期のため");

        assertThat(result).isEqualTo(ApplicationNotificationService.Result.SENT);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("yamada@example.com");
        assertThat(mail.getValue().getFrom()).isEqualTo("noreply@example.com");
        assertThat(mail.getValue().getSubject()).isEqualTo("【申請結果】年休 2026-11-13〜2026-11-16（2.0 日）：拒否");
        assertThat(mail.getValue().getText())
                .contains("山田太郎 さん", "承認者：佐藤部长", "拒否理由：繁忙期のため", "再申請");
    }

    @Test
    void approvalMailHasNoReasonLine() {
        withEmail("yamada@example.com");

        service.notifyApplicant(applicant, "交通費 2026-11-02 480 円", ApplicationStatus.APPROVED, manager, null);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).endsWith("：承認");
        assertThat(mail.getValue().getText()).doesNotContain("拒否理由");
    }

    @Test
    void noMailWithoutApplicantEmail() {
        withEmail("  ");

        assertThat(service.notifyApplicant(applicant, "x", ApplicationStatus.REJECTED, manager, null))
                .isEqualTo(ApplicationNotificationService.Result.NO_APPLICANT_EMAIL);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void noMailWhenSmtpIsNotConfigured() {
        withEmail("yamada@example.com");
        when(mailSenderProvider.getIfAvailable()).thenReturn(null);

        assertThat(service.notifyApplicant(applicant, "x", ApplicationStatus.REJECTED, manager, null))
                .isEqualTo(ApplicationNotificationService.Result.SMTP_NOT_CONFIGURED);
    }

    @Test
    void noMailWhenApplicantActedThemselves() {
        withEmail("yamada@example.com");

        assertThat(service.notifyApplicant(applicant, "x", ApplicationStatus.APPROVED, applicant, null))
                .isEqualTo(ApplicationNotificationService.Result.NOT_APPLICABLE);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    private void withEmail(String email) {
        User stored = TestData.user(1L, "GENERAL", "开发部");
        stored.setEmail(email);
        when(userRepository.findById(1L)).thenReturn(Optional.of(stored));
    }
}
