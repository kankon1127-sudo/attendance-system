package com.wkrepo.system.dto;

import java.math.BigDecimal;

public class MonthlySummaryDTO {
    
    // 勤務実績
    private int workDays;
    private BigDecimal totalWorkHours;
    private BigDecimal totalOvertimeHours;
    
    // 経費
    private BigDecimal nonTaxableTransportExpense; // 非課税交通費
    private BigDecimal travelExpense; // 旅費交通費
    private BigDecimal generalExpense; // 諸経費
    private BigDecimal totalExpense; // 合計
    
    public MonthlySummaryDTO() {
        this.workDays = 0;
        this.totalWorkHours = BigDecimal.ZERO;
        this.totalOvertimeHours = BigDecimal.ZERO;
        this.nonTaxableTransportExpense = BigDecimal.ZERO;
        this.travelExpense = BigDecimal.ZERO;
        this.generalExpense = BigDecimal.ZERO;
        this.totalExpense = BigDecimal.ZERO;
    }
    
    // Getters and Setters
    public int getWorkDays() {
        return workDays;
    }
    
    public void setWorkDays(int workDays) {
        this.workDays = workDays;
    }
    
    public BigDecimal getTotalWorkHours() {
        return totalWorkHours;
    }
    
    public void setTotalWorkHours(BigDecimal totalWorkHours) {
        this.totalWorkHours = totalWorkHours;
    }
    
    public BigDecimal getTotalOvertimeHours() {
        return totalOvertimeHours;
    }
    
    public void setTotalOvertimeHours(BigDecimal totalOvertimeHours) {
        this.totalOvertimeHours = totalOvertimeHours;
    }
    
    public BigDecimal getNonTaxableTransportExpense() {
        return nonTaxableTransportExpense;
    }
    
    public void setNonTaxableTransportExpense(BigDecimal nonTaxableTransportExpense) {
        this.nonTaxableTransportExpense = nonTaxableTransportExpense;
    }
    
    public BigDecimal getTravelExpense() {
        return travelExpense;
    }
    
    public void setTravelExpense(BigDecimal travelExpense) {
        this.travelExpense = travelExpense;
    }
    
    public BigDecimal getGeneralExpense() {
        return generalExpense;
    }
    
    public void setGeneralExpense(BigDecimal generalExpense) {
        this.generalExpense = generalExpense;
    }
    
    public BigDecimal getTotalExpense() {
        return totalExpense;
    }
    
    public void setTotalExpense(BigDecimal totalExpense) {
        this.totalExpense = totalExpense;
    }
}
