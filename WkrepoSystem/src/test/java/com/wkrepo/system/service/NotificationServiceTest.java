package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.Notification;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** システム内通知：重複防止、表示時の言語、メール、他人の通知は開けない */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private ApplicationNotificationService mailService;

    private NotificationService service;
    private final User user = TestData.user(10L, "GENERAL", "開発部");

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);
        service = new NotificationService(notificationRepository, messageSource, mailService, "https://wkrepo.example.com/");
        user.setName("山田太郎");
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void notificationIsCreatedOnceAndMailedInJapanese() {
        user.setEmail("yamada@example.com");

        boolean created = service.notify(user, "MONTHLY_UNSUBMITTED", "notification.monthlyUnsubmitted",
                List.of("2026", "10"), "/monthly-summary?year=2026&month=10", "MONTHLY_UNSUBMITTED:2026-10:w0");

        assertThat(created).isTrue();
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendMail(eq("yamada@example.com"), anyString(), body.capture());
        assertThat(body.getValue()).contains("山田太郎 さん", "2026年10月の月報がまだ提出されていません",
                "https://wkrepo.example.com/monthly-summary?year=2026&month=10");

        when(notificationRepository.existsByUserIdAndDedupeKey(10L, "MONTHLY_UNSUBMITTED:2026-10:w0")).thenReturn(true);
        assertThat(service.notify(user, "MONTHLY_UNSUBMITTED", "notification.monthlyUnsubmitted",
                List.of("2026", "10"), "/x", "MONTHLY_UNSUBMITTED:2026-10:w0")).isFalse();
    }

    @Test
    void noMailWithoutEmailAndNothingForInactiveUser() {
        assertThat(service.notify(user, "T", "notification.approvalOverdue", List.of("3"), "/", "k1")).isTrue();
        verify(mailService, never()).sendMail(anyString(), anyString(), anyString());

        user.setStatus("INACTIVE");
        assertThat(service.notify(user, "T", "notification.approvalOverdue", List.of("3"), "/", "k2")).isFalse();
    }

    @Test
    void messageIsRenderedInViewersLanguage() {
        Notification notification = new Notification();
        notification.setMessageKey("notification.approvalOverdue");
        notification.setArgsJson("[\"3\"]");

        assertThat(service.render(notification, Locale.JAPANESE)).contains("3 件");
        assertThat(service.render(notification, Locale.SIMPLIFIED_CHINESE)).contains("3 件申请");
    }

    @Test
    void openingMarksReadAndOthersCannotOpen() {
        Notification notification = new Notification();
        notification.setUserId(10L);
        notification.setLink("/attendance");
        when(notificationRepository.findByIdAndUserId(1L, 10L)).thenReturn(Optional.of(notification));
        when(notificationRepository.findByIdAndUserId(1L, 99L)).thenReturn(Optional.empty());

        assertThat(service.open(1L, 10L)).contains("/attendance");
        assertThat(notification.getReadAt()).isNotNull();
        assertThat(service.open(1L, 99L)).isEmpty();
    }
}
