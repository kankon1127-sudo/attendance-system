package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.TravelExpenseRepository;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** 承認権限（canApproveApplication）：直属の上司＋代理承認者、月報の 2 段階承認 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationListServiceTest {

    @Mock
    private TravelExpenseRepository travelExpenseRepository;
    @Mock
    private PaidLeaveRepository paidLeaveRepository;
    @Mock
    private MonthlyReportRepository monthlyReportRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private com.wkrepo.system.repository.ApprovalHistoryRepository approvalHistoryRepository;

    @InjectMocks
    private ApplicationListService applicationListService;

    private final User admin = TestData.user(1L, "ADMIN", "管理部");
    private final User manager = TestData.user(2L, "MANAGER", "開発部");               // 申請者の直属の上司
    private final User otherManager = TestData.user(3L, "MANAGER", "営業部");
    private final User sameDeptManager = TestData.user(4L, "MANAGER", "開発部");       // 同じ部署だが上司ではない
    private final User soumu = TestData.user(5L, "SOUMU", "総務部");
    private final User applicant = TestData.user(10L, "GENERAL", "開発部");
    private final User noBoss = TestData.user(11L, "GENERAL", "開発部");

    private List<User> allUsers;

    @BeforeEach
    void setUp() {
        applicant.setManagerId(manager.getId());
        allUsers = List.of(admin, manager, otherManager, sameDeptManager, soumu, applicant, noBoss);
        // 承認経路はリポジトリの内容から計算する（ApprovalRouteService は本物）
        when(userRepository.findByManagerIdIn(any())).thenAnswer(invocation -> {
            Collection<Long> managerIds = invocation.getArgument(0);
            return allUsers.stream().filter(user -> managerIds.contains(user.getManagerId())).toList();
        });
        when(userRepository.findByApprovalDelegateId(anyLong())).thenAnswer(invocation -> {
            Long delegateId = invocation.getArgument(0);
            return allUsers.stream().filter(user -> Objects.equals(user.getApprovalDelegateId(), delegateId)).toList();
        });
        ReflectionTestUtils.setField(applicationListService, "approvalRouteService",
                new ApprovalRouteService(userRepository));
    }

    @Test
    void onlyDirectManagerOrAdminCanApproveTravelExpense() {
        travel(applicant, ApplicationStatus.PENDING);

        assertThat(canApprove("TRAVEL", admin)).isTrue();
        assertThat(canApprove("TRAVEL", manager)).isTrue();
        assertThat(canApprove("TRAVEL", sameDeptManager)).isFalse(); // 同じ部署でも直属の上司でなければ不可
        assertThat(canApprove("TRAVEL", otherManager)).isFalse();
        assertThat(canApprove("TRAVEL", soumu)).isFalse();
        assertThat(canApprove("TRAVEL", applicant)).isFalse();
    }

    @Test
    void delegateCanApproveOnlyDuringThePeriod() {
        leave(applicant);
        manager.setApprovalDelegateId(otherManager.getId());

        manager.setApprovalDelegateFrom(LocalDate.now().minusDays(1));
        manager.setApprovalDelegateTo(LocalDate.now().plusDays(1));
        assertThat(canApprove("PAID_LEAVE", otherManager)).isTrue();
        assertThat(canApprove("PAID_LEAVE", manager)).isTrue(); // 代理期間中も本人は承認できる

        manager.setApprovalDelegateTo(LocalDate.now().minusDays(1));
        assertThat(canApprove("PAID_LEAVE", otherManager)).isFalse();

        manager.setApprovalDelegateFrom(LocalDate.now().plusDays(1));
        manager.setApprovalDelegateTo(LocalDate.now().plusDays(3));
        assertThat(canApprove("PAID_LEAVE", otherManager)).isFalse();
    }

    @Test
    void applicantWithoutManagerIsApprovedByAdminOnly() {
        travel(noBoss, ApplicationStatus.PENDING);

        assertThat(canApprove("TRAVEL", admin)).isTrue();
        assertThat(canApprove("TRAVEL", manager)).isFalse();
        assertThat(canApprove("TRAVEL", sameDeptManager)).isFalse();
    }

    @Test
    void approvedTravelExpenseCannotBeApprovedAgain() {
        travel(applicant, ApplicationStatus.APPROVED);

        assertThat(canApprove("TRAVEL", admin)).isFalse();
        assertThat(canApprove("TRAVEL", manager)).isFalse();
    }

    @Test
    void nobodyCanApproveOwnApplication() {
        travel(manager, ApplicationStatus.PENDING);

        assertThat(canApprove("TRAVEL", manager)).isFalse();
    }

    @Test
    void submittedMonthlyReportIsApprovedByDesignatedApproverOrAdmin() {
        monthlyReport(ApplicationStatus.SUBMITTED, manager.getId());

        assertThat(canApprove("MONTHLY_REPORT", admin)).isTrue();
        assertThat(canApprove("MONTHLY_REPORT", manager)).isTrue();
        assertThat(canApprove("MONTHLY_REPORT", sameDeptManager)).isFalse();
        assertThat(canApprove("MONTHLY_REPORT", otherManager)).isFalse();
        assertThat(canApprove("MONTHLY_REPORT", soumu)).isFalse();
    }

    @Test
    void delegateOfDesignatedApproverCanApproveMonthlyReport() {
        monthlyReport(ApplicationStatus.SUBMITTED, manager.getId());
        manager.setApprovalDelegateId(otherManager.getId());
        manager.setApprovalDelegateFrom(LocalDate.now());
        manager.setApprovalDelegateTo(LocalDate.now());

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
    void firstStageApproverCannotAlsoFinalize() {
        // ADMIN が 1 次承認した月報：同じ ADMIN は最終承認できず、総務（または別の ADMIN）が行う
        monthlyReport(ApplicationStatus.MANAGER_APPROVED, manager.getId());
        com.wkrepo.system.entity.ApprovalHistory firstStage = new com.wkrepo.system.entity.ApprovalHistory();
        firstStage.setActorId(admin.getId());
        when(approvalHistoryRepository.findFirstByApplicationTypeAndApplicationIdAndNextStatusOrderByActionAtDescIdDesc(
                "MONTHLY_REPORT", 100L, "MANAGER_APPROVED")).thenReturn(Optional.of(firstStage));

        assertThat(canApprove("MONTHLY_REPORT", admin)).isFalse();
        assertThat(canApprove("MONTHLY_REPORT", soumu)).isTrue();
        assertThat(canApprove("MONTHLY_REPORT", TestData.user(8L, "ADMIN", "管理部"))).isTrue();
    }

    @Test
    void onlySoumuOrAdminCanReopenFinalizedReportOfOthers() {
        MonthlyReport report = new MonthlyReport();
        report.setUser(applicant);
        report.setStatus(ApplicationStatus.FINALIZED);

        assertThat(ApplicationListService.canReopen(soumu, report)).isTrue();
        assertThat(ApplicationListService.canReopen(admin, report)).isTrue();
        assertThat(ApplicationListService.canReopen(manager, report)).isFalse();
        assertThat(ApplicationListService.canReopen(applicant, report)).isFalse();

        report.setUser(soumu);
        assertThat(ApplicationListService.canReopen(soumu, report)).isFalse(); // 自分の月報は不可

        report.setUser(applicant);
        report.setStatus(ApplicationStatus.MANAGER_APPROVED); // 未確定は通常の拒否で戻す
        assertThat(ApplicationListService.canReopen(soumu, report)).isFalse();
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

    private void travel(User owner, ApplicationStatus status) {
        TravelExpense expense = new TravelExpense();
        expense.setUser(owner);
        expense.setStatus(status);
        when(travelExpenseRepository.findById(100L)).thenReturn(Optional.of(expense));
    }

    private void leave(User owner) {
        PaidLeave leave = new PaidLeave();
        leave.setUser(owner);
        leave.setStatus(ApplicationStatus.PENDING);
        when(paidLeaveRepository.findById(100L)).thenReturn(Optional.of(leave));
    }

    private void monthlyReport(ApplicationStatus status, Long approverId) {
        MonthlyReport report = new MonthlyReport();
        report.setId(100L);
        report.setUser(applicant);
        report.setStatus(status);
        report.setApproverId(approverId);
        when(monthlyReportRepository.findById(100L)).thenReturn(Optional.of(report));
    }
}
