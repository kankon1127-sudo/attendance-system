package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 定期リマインド：月報の未提出、承認の滞留、36 協定（送る相手・時期・重複防止のキー） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReminderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Mock
    private UserRepository userRepository;
    @Mock
    private MonthlyReportRepository monthlyReportRepository;
    @Mock
    private ApplicationListService applicationListService;
    @Mock
    private Agreement36Service agreement36Service;
    @Mock
    private NotificationService notificationService;

    private ReminderService service;

    private final User admin = TestData.user(1L, "ADMIN", "管理部");
    private final User manager = TestData.user(2L, "MANAGER", "開発部");
    private final User soumu = TestData.user(5L, "SOUMU", "総務部");
    private final User submitted = TestData.user(10L, "GENERAL", "開発部");
    private final User draft = TestData.user(11L, "GENERAL", "開発部");
    private final User none = TestData.user(12L, "GENERAL", "開発部");
    private final User inactive = TestData.user(13L, "GENERAL", "開発部");

    @BeforeEach
    void setUp() {
        manager.setManagerId(admin.getId());
        soumu.setManagerId(admin.getId());
        for (User user : List.of(submitted, draft, none, inactive)) {
            user.setManagerId(manager.getId());
        }
        inactive.setStatus("INACTIVE");
        when(userRepository.findAll()).thenReturn(List.of(admin, manager, soumu, submitted, draft, none, inactive));
        when(userRepository.findById(manager.getId())).thenReturn(Optional.of(manager));
        when(monthlyReportRepository.findByUserIdAndYearAndMonth(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        report(submitted, ApplicationStatus.SUBMITTED);
        report(manager, ApplicationStatus.FINALIZED);
        report(soumu, ApplicationStatus.MANAGER_APPROVED);
        report(draft, ApplicationStatus.DRAFT);
        when(notificationService.notify(any(), anyString(), anyString(), anyList(), anyString(), anyString())).thenReturn(true);
        service = new ReminderService(userRepository, monthlyReportRepository, applicationListService,
                agreement36Service, notificationService, 3, new BigDecimal("40"),
                Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
    }

    @Test
    void unsubmittedAreThoseWithManagerWhoseReportIsMissingDraftOrRejected() {
        assertThat(service.unsubmittedUsers(YearMonth.of(2026, 10))).containsExactly(draft, none);
    }

    @Test
    void monthlyReminderStartsOnConfiguredDayAndRepeatsWeekly() {
        assertThat(service.remindUnsubmittedMonthlyReports(LocalDate.of(2026, 11, 2))).isZero();

        // 12 日 = 3 日から 9 日目 → 2 週目（w1）。本人 2 名 + 総務 1 名
        assertThat(service.remindUnsubmittedMonthlyReports(TODAY)).isEqualTo(3);
        verify(notificationService).notify(eq(draft), eq("MONTHLY_UNSUBMITTED"), eq("notification.monthlyUnsubmitted"),
                eq(List.of("2026", "10")), anyString(), eq("MONTHLY_UNSUBMITTED:2026-10:w1"));
        verify(notificationService).notify(eq(soumu), anyString(), eq("notification.monthlyUnsubmittedSummary"),
                eq(List.of("2026", "10", "2")), eq("/closing?year=2026&month=10"), eq("MONTHLY_UNSUBMITTED_SUMMARY:2026-10:w1"));
        verify(notificationService, never()).notify(eq(inactive), anyString(), anyString(), anyList(), anyString(), anyString());
    }

    @Test
    void overdueApprovalsAreNotifiedWeeklyToApprovers() {
        ApplicationItem old = new ApplicationItem();
        old.setCanApprove(true);
        old.setApplicationDate(LocalDateTime.now().minusDays(8));
        ApplicationItem fresh = new ApplicationItem();
        fresh.setCanApprove(true);
        fresh.setApplicationDate(LocalDateTime.now().minusDays(1));
        when(applicationListService.getPendingApprovalsForUser(any())).thenReturn(List.of());
        when(applicationListService.getPendingApprovalsForUser(manager)).thenReturn(List.of(old, fresh));

        assertThat(service.remindOverdueApprovals(TODAY)).isEqualTo(1);
        verify(notificationService).notify(eq(manager), eq("APPROVAL_OVERDUE"), eq("notification.approvalOverdue"),
                eq(List.of("1")), eq("/application/list"), eq("APPROVAL_OVERDUE:2026W46"));
        verify(applicationListService, never()).getPendingApprovalsForUser(draft); // 一般社員は対象外
    }

    @Test
    void agreement36AlertGoesToUserManagerAndSoumu() {
        Agreement36Service.Result quiet = new Agreement36Service.Result(BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, 0, List.of());
        when(agreement36Service.check(any(), any())).thenReturn(quiet);
        Agreement36Service.Result over = new Agreement36Service.Result(new BigDecimal("46.5"), BigDecimal.ZERO,
                new BigDecimal("46.5"), 1, List.of(new Agreement36Service.Alert(false, "agreement36.monthlyOvertime", new Object[] {"46.50"})));
        when(agreement36Service.check(eq(draft), any())).thenReturn(over);

        assertThat(service.remindAgreement36(TODAY)).isEqualTo(4); // 本人（40h 超）・本人（警告）・上司・総務
        ArgumentCaptor<User> recipients = ArgumentCaptor.forClass(User.class);
        verify(notificationService, times(4)).notify(recipients.capture(), eq("AGREEMENT36"), anyString(), anyList(),
                anyString(), anyString());
        assertThat(recipients.getAllValues()).containsExactly(draft, draft, manager, soumu);
        verify(notificationService).notify(eq(manager), anyString(), eq("notification.agreement36Member"),
                eq(List.of("User11", "1", "46.5")), eq("/team/attendance?userId=11&year=2026&month=11"),
                eq("AGREEMENT36:2026-11:11:1"));
    }

    @Test
    void soumuRecipientsFallBackToAdmin() {
        soumu.setStatus("INACTIVE");

        assertThat(service.soumuRecipients()).containsExactly(admin);
    }

    private void report(User user, ApplicationStatus status) {
        MonthlyReport report = new MonthlyReport();
        report.setStatus(status);
        when(monthlyReportRepository.findByUserIdAndYearAndMonth(user.getId(), 2026, 10)).thenReturn(Optional.of(report));
    }
}
