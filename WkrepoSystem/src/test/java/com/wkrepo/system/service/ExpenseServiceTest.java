package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.GeneralExpenseRepository;
import com.wkrepo.system.repository.TravelExpenseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 交通費・諸経費：リクエストから状態や id を設定させない、承認済みは削除させない */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExpenseServiceTest {

    @Mock
    private TravelExpenseRepository travelExpenseRepository;
    @Mock
    private GeneralExpenseRepository generalExpenseRepository;
    @Mock
    private AttendanceService attendanceService;

    @InjectMocks
    private TravelExpenseService travelExpenseService;
    @InjectMocks
    private GeneralExpenseService generalExpenseService;

    private final User owner = TestData.user(1L, "GENERAL", "开发部");

    // ---------- 交通費 ----------

    @Test
    void newTravelExpenseAlwaysStartsAsPending() {
        when(travelExpenseRepository.save(any(TravelExpense.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TravelExpense expense = travel(null, ApplicationStatus.APPROVED); // 承認済みを送り込まれても

        TravelExpense saved = travelExpenseService.saveTravelExpense(expense);

        assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void travelExpenseWithIdIsRejected() {
        assertThatThrownBy(() -> travelExpenseService.saveTravelExpense(travel(99L, null)))
                .hasMessage("application.error.invalidRequest");
        verify(travelExpenseRepository, never()).save(any());
    }

    @Test
    void approvedTravelExpenseCannotBeDeleted() {
        when(travelExpenseRepository.findById(5L)).thenReturn(Optional.of(travel(5L, ApplicationStatus.APPROVED)));

        assertThatThrownBy(() -> travelExpenseService.deleteTravelExpense(5L, owner))
                .hasMessage("expense.error.approvedNotEditable");
        verify(travelExpenseRepository, never()).delete(any());
    }

    @Test
    void pendingAndRejectedTravelExpenseCanBeDeleted() {
        TravelExpense pending = travel(5L, ApplicationStatus.PENDING);
        when(travelExpenseRepository.findById(5L)).thenReturn(Optional.of(pending));
        travelExpenseService.deleteTravelExpense(5L, owner);
        verify(travelExpenseRepository).delete(pending);

        TravelExpense rejected = travel(6L, ApplicationStatus.REJECTED);
        when(travelExpenseRepository.findById(6L)).thenReturn(Optional.of(rejected));
        travelExpenseService.deleteTravelExpense(6L, owner);
        verify(travelExpenseRepository).delete(rejected);
    }

    // ---------- 諸経費 ----------

    @Test
    void newGeneralExpenseAlwaysStartsAsPending() {
        when(generalExpenseRepository.save(any(GeneralExpense.class))).thenAnswer(invocation -> invocation.getArgument(0));
        GeneralExpense expense = general(null, ApplicationStatus.FINALIZED);

        assertThat(generalExpenseService.saveGeneralExpense(expense).getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    @Test
    void generalExpenseWithIdIsRejected() {
        assertThatThrownBy(() -> generalExpenseService.saveGeneralExpense(general(99L, null)))
                .hasMessage("application.error.invalidRequest");
        verify(generalExpenseRepository, never()).save(any());
    }

    @Test
    void approvedGeneralExpenseCannotBeDeleted() {
        when(generalExpenseRepository.findById(7L)).thenReturn(Optional.of(general(7L, ApplicationStatus.APPROVED)));

        assertThatThrownBy(() -> generalExpenseService.deleteGeneralExpense(7L, owner))
                .hasMessage("expense.error.approvedNotEditable");
        verify(generalExpenseRepository, never()).delete(any());
    }

    private TravelExpense travel(Long id, ApplicationStatus status) {
        TravelExpense expense = new TravelExpense();
        expense.setId(id);
        expense.setUser(owner);
        expense.setExpenseDate(LocalDate.of(2026, 11, 2));
        expense.setAmount(new BigDecimal("480"));
        expense.setStatus(status);
        return expense;
    }

    private GeneralExpense general(Long id, ApplicationStatus status) {
        GeneralExpense expense = new GeneralExpense();
        expense.setId(id);
        expense.setUser(owner);
        expense.setDate(LocalDate.of(2026, 11, 2));
        expense.setAmount(new BigDecimal("1200"));
        expense.setStatus(status);
        return expense;
    }
}
