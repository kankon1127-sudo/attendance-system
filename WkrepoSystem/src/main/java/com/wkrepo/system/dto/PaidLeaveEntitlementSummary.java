package com.wkrepo.system.dto;

import java.math.BigDecimal;

public class PaidLeaveEntitlementSummary {

    private final Long userId;
    private final String employeeId;
    private final String name;
    private final String department;
    private final BigDecimal grantedDays;

    public PaidLeaveEntitlementSummary(
            Long userId, String employeeId, String name, String department, BigDecimal grantedDays) {
        this.userId = userId;
        this.employeeId = employeeId;
        this.name = name;
        this.department = department;
        this.grantedDays = grantedDays;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getName() {
        return name;
    }

    public String getDepartment() {
        return department;
    }

    public BigDecimal getGrantedDays() {
        return grantedDays;
    }
}
