package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.TravelExpenseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 审批权限（canApproveApplication）的规则 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationListServiceTest {

    @Mock
    private TravelExpenseRepository travelExpenseRepository;
    @Mock
    private MonthlyReportRepository monthlyReportRepository;

    @InjectMocks
    private ApplicationListService applicationListService;

    private final User applicant = TestData.user(10L, "GENERAL", "开发部");
    private final User admin = TestData.user(1L, "ADMIN", "管理部");
    private final User manager = TestData.user(2L, "MANAGER", "开发部");
    private final User otherManager = TestData.user(3L, "MANAGER", "営業部");
    private final User soumu = TestData.user(5L, "SOUMU", "総務部");

    @Test
    void travelExpenseApprovalRules() {
        travel(ApplicationStatus.PENDING);

        assertThat(canApprove("TRAVEL", admin)).isTrue();
        assertThat(canApprove("TRAVEL", manager)).isTrue();
        assertThat(canApprove("TRAVEL", otherManager)).isFalse();
        assertThat(canApprove("TRAVEL", soumu)).isFalse();
        assertThat(canApprove("TRAVEL", applicant)).isFalse();
    }

    @Test
    void approvedTravelExpenseCannotBeApprovedAgain() {
        travel(ApplicationStatus.APPROVED);

        assertThat(canApprove("TRAVEL", admin)).isFalse();
        assertThat(canApprove("TRAVEL", manager)).isFalse();
    }

    @Test
    void nobodyCanApproveOwnApplication() {
        TravelExpense expense = new TravelExpense();
        expense.setUser(manager);
        expense.setStatus(ApplicationStatus.PENDING);
        when(travelExpenseRepository.findById(100L)).thenReturn(Optional.of(expense));

        assertThat(canApprove("TRAVEL", manager)).isFalse();
    }

    @Test
    void submittedMonthlyReportIsApprovedByManagerOrAdmin() {
        monthlyReport(ApplicationStatus.SUBMITTED, null);

        assertThat(canApprove("MONTHLY_REPORT", admin)).isTrue();
        assertThat(canApprove("MONTHLY_REPORT", manager)).isTrue();
        assertThat(canApprove("MONTHLY_REPORT", otherManager)).isFalse();
        assertThat(canApprove("MONTHLY_REPORT", soumu)).isFalse();
    }

    @Test
    void designatedApproverFromOtherDepartmentCanApproveMonthlyReport() {
        monthlyReport(ApplicationStatus.SUBMITTED, otherManager.getId());

        assertThat(canApprove("MONTHLY_REPORT", otherManager)).isTrue();
    }

    @Test
    void managerApprovedMonthlyReportIsFinalizedBySoumuOrAdmin() {
        monthlyReport(ApplicationStatus.MANAGER_APPROVED, manager.getId());

        assertThat(canApprove("MONTHLY_REPORT", soumu)).isTrue();
        assertThat(canApprove("MONTHLY_REPORT", admin)).isTrue();
        assertThat(canApprove("MONTHLY_REPORT", manager)).isFalse();
    }

    @Test
    void draftMonthlyReportCannotBeApproved() {
        monthlyReport(ApplicationStatus.DRAFT, manager.getId());

        assertThat(canApprove("MONTHLY_REPORT", manager)).isFalse();
        assertThat(canApprove("MONTHLY_REPORT", admin)).isFalse();
    }

    private boolean canApprove(String type, User currentUser) {
        return applicationListService.canApproveApplication(100L, type, currentUser);
    }

    private void travel(ApplicationStatus status) {
        TravelExpense expense = new TravelExpense();
        expense.setUser(applicant);
        expense.setStatus(status);
        when(travelExpenseRepository.findById(100L)).thenReturn(Optional.of(expense));
    }

    private void monthlyReport(ApplicationStatus status, Long approverId) {
        MonthlyReport report = new MonthlyReport();
        report.setUser(applicant);
        report.setStatus(status);
        report.setApproverId(approverId);
        when(monthlyReportRepository.findById(100L)).thenReturn(Optional.of(report));
    }
}
