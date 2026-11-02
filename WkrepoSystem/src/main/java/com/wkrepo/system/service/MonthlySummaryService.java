package com.wkrepo.system.service;

import com.wkrepo.system.dto.MonthlySummaryDTO;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
public class MonthlySummaryService {
    
    @Autowired
    private AttendanceService attendanceService;
    
    @Autowired
    private TravelExpenseService travelExpenseService;
    
    @Autowired
    private GeneralExpenseService generalExpenseService;
    
    public MonthlySummaryDTO calculateMonthlySummary(User user, int year, int month) {
        MonthlySummaryDTO summary = new MonthlySummaryDTO();
        
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();
        
        // 1. 统计勤務実績
        calculateAttendance(summary, user, startDate, endDate);
        
        // 2. 統計経費
        calculateExpenses(summary, user, startDate, endDate);
        
        return summary;
    }
    
    private void calculateAttendance(MonthlySummaryDTO summary, User user, LocalDate startDate, LocalDate endDate) {
        List<Attendance> attendances = attendanceService.getAttendancesByUserAndDateRange(user, startDate, endDate);
        // 与勤怠页面、首页、Excel 使用同一个统计方法
        AttendanceService.MonthlyStatistics statistics =
                attendanceService.calculateMonthlyStatistics(attendances, YearMonth.from(startDate));

        summary.setWorkDays(statistics.workDays());
        summary.setTotalWorkHours(statistics.workHours());
        summary.setTotalOvertimeHours(statistics.overtimeHours());
    }

    /**
     * 经费合计只计入承認済（与 Excel 导出一致）；申請中的金额另外合计，供参考。
     */
    private void calculateExpenses(MonthlySummaryDTO summary, User user, LocalDate startDate, LocalDate endDate) {
        BigDecimal travelExpenseTotal = BigDecimal.ZERO;
        BigDecimal nonTaxableTotal = BigDecimal.ZERO;
        BigDecimal generalExpenseTotal = BigDecimal.ZERO;
        BigDecimal pendingTotal = BigDecimal.ZERO;

        // 統計旅費交通費
        List<TravelExpense> travelExpenses = travelExpenseService.getTravelExpensesByUserAndDateRange(user, startDate, endDate);
        for (TravelExpense expense : travelExpenses) {
            if (expense.getAmount() == null) {
                continue;
            }
            if (expense.getStatus() == ApplicationStatus.PENDING) {
                pendingTotal = pendingTotal.add(expense.getAmount());
            } else if (expense.getStatus() == ApplicationStatus.APPROVED) {
                if ("非課税交通費".equals(expense.getType())) {
                    nonTaxableTotal = nonTaxableTotal.add(expense.getAmount());
                } else {
                    travelExpenseTotal = travelExpenseTotal.add(expense.getAmount());
                }
            }
        }

        // 統計諸経費
        List<GeneralExpense> generalExpenses = generalExpenseService.getGeneralExpensesByUserAndDateRange(user, startDate, endDate);
        for (GeneralExpense expense : generalExpenses) {
            if (expense.getAmount() == null) {
                continue;
            }
            if (expense.getStatus() == ApplicationStatus.PENDING) {
                pendingTotal = pendingTotal.add(expense.getAmount());
            } else if (expense.getStatus() == ApplicationStatus.APPROVED) {
                generalExpenseTotal = generalExpenseTotal.add(expense.getAmount());
            }
        }

        summary.setNonTaxableTransportExpense(nonTaxableTotal);
        summary.setTravelExpense(travelExpenseTotal);
        summary.setGeneralExpense(generalExpenseTotal);
        summary.setTotalExpense(nonTaxableTotal.add(travelExpenseTotal).add(generalExpenseTotal));
        summary.setPendingExpense(pendingTotal);
    }
}
