package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.MonthlyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 確定済み月報の差し戻し（#47）：総務・管理者のみ、理由必須、状態は「拒否」に戻る */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonthlyReportServiceTest {

    @Mock
    private MonthlyReportRepository monthlyReportRepository;
    @Mock
    private ApprovalHistoryService approvalHistoryService;

    @InjectMocks
    private MonthlyReportService service;

    private final User employee = TestData.user(10L, "GENERAL", "開発部");
    private final User soumu = TestData.user(5L, "SOUMU", "総務部");
    private final User manager = TestData.user(2L, "MANAGER", "開発部");
    private final MonthlyReport report = new MonthlyReport();

    @BeforeEach
    void setUp() {
        report.setUser(employee);
        report.setStatus(ApplicationStatus.FINALIZED);
        when(monthlyReportRepository.findById(1L)).thenReturn(Optional.of(report));
    }

    @Test
    void soumuReopensFinalizedReportWithReason() {
        service.reopenReport(1L, soumu, "交通費の計上漏れ");

        verify(approvalHistoryService).transition("MONTHLY_REPORT", 1L, ApplicationStatus.REJECTED, soumu, "交通費の計上漏れ");
    }

    @Test
    void reasonIsRequired() {
        assertThatThrownBy(() -> service.reopenReport(1L, soumu, "  "))
                .hasMessage("monthly.error.reopenReasonRequired");
        verify(approvalHistoryService, never()).transition(anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void managerCannotReopen() {
        assertThatThrownBy(() -> service.reopenReport(1L, manager, "理由"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void notFinalizedReportCannotBeReopened() {
        report.setStatus(ApplicationStatus.SUBMITTED);

        assertThatThrownBy(() -> service.reopenReport(1L, soumu, "理由"))
                .isInstanceOf(SecurityException.class);
    }
}
