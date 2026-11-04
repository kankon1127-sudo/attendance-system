package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.dto.MonthlySummaryDTO;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonthlySummaryServiceTest {

    @Mock
    private AttendanceService attendanceService;
    @Mock
    private TravelExpenseService travelExpenseService;
    @Mock
    private GeneralExpenseService generalExpenseService;

    @InjectMocks
    private MonthlySummaryService monthlySummaryService;

    private final User user = TestData.user(1L, "GENERAL", "开发部");

    @BeforeEach
    void setUp() {
        // 月次統計は実際の実装を使う（勤怠画面・Excel と共通のもの）
        when(attendanceService.calculateMonthlyStatistics(any(), any())).thenCallRealMethod();
        when(attendanceService.getAttendancesByUserAndDateRange(eq(user), any(), any())).thenReturn(List.of(
                TestData.attendanceWithHours(user, "2026-11-02", "出社", "8.00", "0.00"),
                TestData.attendanceWithHours(user, "2026-11-04", "有給", "0.00", "0.00"),
                TestData.attendanceWithHours(user, "2026-11-05", "欠勤", "0.00", "0.00"),
                TestData.attendanceWithHours(user, "2026-11-06", "在宅", "9.00", "1.00")));
        when(travelExpenseService.getTravelExpensesByUserAndDateRange(eq(user), any(), any())).thenReturn(List.of(
                travel("非課税交通費", "1000", ApplicationStatus.APPROVED),
                travel("旅費交通費", "3000", ApplicationStatus.REJECTED),
                travel("旅費交通費", "500", ApplicationStatus.PENDING),
                travel("旅費交通費", "200", ApplicationStatus.APPROVED)));
        when(generalExpenseService.getGeneralExpensesByUserAndDateRange(eq(user), any(), any())).thenReturn(List.of(
                general("2000", ApplicationStatus.APPROVED),
                general("700", ApplicationStatus.PENDING)));
    }

    @Test
    void workDaysExcludePaidLeaveAndAbsence() {
        MonthlySummaryDTO summary = monthlySummaryService.calculateMonthlySummary(user, 2026, 11);

        assertThat(summary.getWorkDays()).isEqualTo(2);
        assertThat(summary.getTotalWorkHours()).isEqualByComparingTo("17.00");
        assertThat(summary.getTotalOvertimeHours()).isEqualByComparingTo("1.00");
    }

    @Test
    void expenseTotalsCountOnlyApproved() {
        MonthlySummaryDTO summary = monthlySummaryService.calculateMonthlySummary(user, 2026, 11);

        assertThat(summary.getNonTaxableTransportExpense()).isEqualByComparingTo("1000");
        assertThat(summary.getTravelExpense()).isEqualByComparingTo("200");
        assertThat(summary.getGeneralExpense()).isEqualByComparingTo("2000");
        assertThat(summary.getTotalExpense()).isEqualByComparingTo("3200");
        assertThat(summary.getPendingExpense()).isEqualByComparingTo("1200");
    }

    private TravelExpense travel(String type, String amount, ApplicationStatus status) {
        TravelExpense expense = new TravelExpense();
        expense.setUser(user);
        expense.setExpenseDate(LocalDate.of(2026, 11, 2));
        expense.setType(type);
        expense.setAmount(new BigDecimal(amount));
        expense.setStatus(status);
        return expense;
    }

    private GeneralExpense general(String amount, ApplicationStatus status) {
        GeneralExpense expense = new GeneralExpense();
        expense.setUser(user);
        expense.setDate(LocalDate.of(2026, 11, 2));
        expense.setAmount(new BigDecimal(amount));
        expense.setStatus(status);
        return expense;
    }
}
