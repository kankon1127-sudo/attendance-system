package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.entity.ApprovalHistory;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.ApprovalHistoryRepository;
import com.wkrepo.system.repository.GeneralExpenseRepository;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.TravelExpenseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 承認履歴：拒否理由の保存と一覧への表示、通知用の申請者・要約 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalHistoryServiceTest {

    @Mock
    private ApprovalHistoryRepository historyRepository;
    @Mock
    private TravelExpenseRepository travelExpenseRepository;
    @Mock
    private GeneralExpenseRepository generalExpenseRepository;
    @Mock
    private PaidLeaveRepository paidLeaveRepository;
    @Mock
    private MonthlyReportRepository monthlyReportRepository;

    @InjectMocks
    private ApprovalHistoryService service;

    private final User applicant = TestData.user(1L, "GENERAL", "开发部");
    private final User manager = TestData.user(2L, "MANAGER", "开发部");

    @Test
    void rejectReasonIsTrimmedAndStoredWithApplicantAndSummary() {
        PaidLeave leave = new PaidLeave();
        leave.setUser(applicant);
        leave.setStartDate(LocalDate.of(2026, 11, 13));
        leave.setEndDate(LocalDate.of(2026, 11, 16));
        leave.setLeaveDays(new BigDecimal("2.0"));
        leave.setStatus(ApplicationStatus.PENDING);
        when(paidLeaveRepository.findById(10L)).thenReturn(Optional.of(leave));

        ApprovalHistoryService.Transition result =
                service.transition("PAID_LEAVE", 10L, ApplicationStatus.REJECTED, manager, "  繁忙期のため  ");

        assertThat(leave.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(result.applicant()).isSameAs(applicant);
        assertThat(result.summary()).isEqualTo("年休 2026-11-13〜2026-11-16（2.0 日）");
        ArgumentCaptor<ApprovalHistory> saved = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository).save(saved.capture());
        assertThat(saved.getValue().getComment()).isEqualTo("繁忙期のため");
        assertThat(saved.getValue().getPreviousStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().getNextStatus()).isEqualTo("REJECTED");
    }

    @Test
    void blankReasonIsStoredAsNull() {
        when(travelExpenseRepository.findById(5L)).thenReturn(Optional.of(travel()));

        ApprovalHistoryService.Transition result =
                service.transition("TRAVEL", 5L, ApplicationStatus.REJECTED, manager, "   ");

        assertThat(result.summary()).isEqualTo("交通費 2026-11-02 1,480 円");
        ArgumentCaptor<ApprovalHistory> saved = ArgumentCaptor.forClass(ApprovalHistory.class);
        verify(historyRepository).save(saved.capture());
        assertThat(saved.getValue().getComment()).isNull();
    }

    @Test
    void tooLongReasonIsRejectedBeforeAnythingChanges() {
        TravelExpense expense = travel();
        when(travelExpenseRepository.findById(5L)).thenReturn(Optional.of(expense));

        assertThatThrownBy(() -> service.transition("TRAVEL", 5L, ApplicationStatus.REJECTED, manager, "あ".repeat(501)))
                .hasMessage("application.error.commentTooLong");
        assertThat(expense.getStatus()).isEqualTo(ApplicationStatus.PENDING);
        verify(travelExpenseRepository, never()).save(any());
        verify(historyRepository, never()).save(any());

        service.transition("TRAVEL", 5L, ApplicationStatus.REJECTED, manager, "あ".repeat(500));
        assertThat(expense.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
    }

    @Test
    void listShowsLatestRejectReasonOnlyForRejectedItems() {
        ApplicationItem rejectedTwice = item(1L, "PAID_LEAVE", ApplicationStatus.REJECTED);
        ApplicationItem rejectedWithoutReason = item(2L, "PAID_LEAVE", ApplicationStatus.REJECTED);
        ApplicationItem approved = item(3L, "PAID_LEAVE", ApplicationStatus.APPROVED);
        when(historyRepository.findByApplicationTypeAndApplicationIdInAndNextStatusOrderByActionAtAscIdAsc(
                eq("PAID_LEAVE"), anyCollection(), eq("REJECTED")))
                .thenReturn(List.of(history(1L, "古い理由"), history(1L, "新しい理由"), history(2L, null)));

        service.fillRejectReasons(List.of(rejectedTwice, rejectedWithoutReason, approved));

        assertThat(rejectedTwice.getRejectReason()).isEqualTo("新しい理由");
        assertThat(rejectedWithoutReason.getRejectReason()).isNull();
        assertThat(approved.getRejectReason()).isNull();
    }

    @Test
    void noQueryWhenNothingIsRejected() {
        service.fillRejectReasons(List.of(item(3L, "TRAVEL", ApplicationStatus.PENDING)));

        verify(historyRepository, never())
                .findByApplicationTypeAndApplicationIdInAndNextStatusOrderByActionAtAscIdAsc(any(), any(), any());
    }

    private TravelExpense travel() {
        TravelExpense expense = new TravelExpense();
        expense.setUser(applicant);
        expense.setExpenseDate(LocalDate.of(2026, 11, 2));
        expense.setAmount(new BigDecimal("1480"));
        expense.setStatus(ApplicationStatus.PENDING);
        return expense;
    }

    private ApplicationItem item(Long id, String type, ApplicationStatus status) {
        ApplicationItem item = new ApplicationItem();
        item.setId(id);
        item.setApplicationType(type);
        item.setStatus(status);
        return item;
    }

    private ApprovalHistory history(Long applicationId, String comment) {
        ApprovalHistory history = new ApprovalHistory();
        history.setApplicationType("PAID_LEAVE");
        history.setApplicationId(applicationId);
        history.setNextStatus("REJECTED");
        history.setComment(comment);
        return history;
    }
}
