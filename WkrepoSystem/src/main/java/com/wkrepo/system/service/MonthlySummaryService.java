package com.wkrepo.system.service;

import com.wkrepo.system.dto.MonthlySummaryDTO;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
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
        
        int workDays = 0;
        BigDecimal totalWorkHours = BigDecimal.ZERO;
        BigDecimal totalOvertimeHours = BigDecimal.ZERO;
        
        for (Attendance attendance : attendances) {
            // 稼働日数：只有非休日、非未出勤的才算
            String status = attendance.getAgreement36Status();
            if (status != null && !status.equals("休日") && !status.equals("未出勤") && !status.equals("-")) {
                workDays++;
            }
            
            // 累計實働時間
            if (attendance.getWorkHours() != null) {
                totalWorkHours = totalWorkHours.add(attendance.getWorkHours());
            }
            
            // 累計残業時間
            if (attendance.getOvertimeHours() != null) {
                totalOvertimeHours = totalOvertimeHours.add(attendance.getOvertimeHours());
            }
        }
        
        summary.setWorkDays(workDays);
        summary.setTotalWorkHours(totalWorkHours);
        summary.setTotalOvertimeHours(totalOvertimeHours);
    }
    
    private void calculateExpenses(MonthlySummaryDTO summary, User user, LocalDate startDate, LocalDate endDate) {
        // 統計旅費交通費
        List<TravelExpense> travelExpenses = travelExpenseService.getTravelExpensesByUserAndDateRange(user, startDate, endDate);
        BigDecimal travelExpenseTotal = BigDecimal.ZERO;
        BigDecimal nonTaxableTotal = BigDecimal.ZERO;
        
        for (TravelExpense expense : travelExpenses) {
            if (expense.getAmount() != null) {
                if ("非課税交通費".equals(expense.getType())) {
                    nonTaxableTotal = nonTaxableTotal.add(expense.getAmount());
                } else {
                    travelExpenseTotal = travelExpenseTotal.add(expense.getAmount());
                }
            }
        }
        
        // 統計諸経費
        List<GeneralExpense> generalExpenses = generalExpenseService.getGeneralExpensesByUserAndDateRange(user, startDate, endDate);
        BigDecimal generalExpenseTotal = BigDecimal.ZERO;
        
        for (GeneralExpense expense : generalExpenses) {
            if (expense.getAmount() != null) {
                generalExpenseTotal = generalExpenseTotal.add(expense.getAmount());
            }
        }
        
        summary.setNonTaxableTransportExpense(nonTaxableTotal);
        summary.setTravelExpense(travelExpenseTotal);
        summary.setGeneralExpense(generalExpenseTotal);
        summary.setTotalExpense(nonTaxableTotal.add(travelExpenseTotal).add(generalExpenseTotal));
    }
}
