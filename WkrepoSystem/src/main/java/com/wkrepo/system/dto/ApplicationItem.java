package com.wkrepo.system.dto;

import com.wkrepo.system.enums.ApplicationStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public class ApplicationItem {
    
    private Long id;
    private String applicantName;
    private String applicantDepartment;
    private String applicationType; // TRAVEL, GENERAL, PAID_LEAVE
    private String applicationTypeLabel; // 交通费, 諸経費, 年休
    private LocalDateTime applicationDate;
    private BigDecimal amount; // 金額（交通费、诸费用）或天数（年假）
    private String amountLabel;
    private ApplicationStatus status;
    private boolean canApprove;
    private boolean canWithdraw;

    public ApplicationItem() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public void setApplicantName(String applicantName) {
        this.applicantName = applicantName;
    }

    public String getApplicantDepartment() {
        return applicantDepartment;
    }

    public void setApplicantDepartment(String applicantDepartment) {
        this.applicantDepartment = applicantDepartment;
    }

    public String getApplicationType() {
        return applicationType;
    }

    public void setApplicationType(String applicationType) {
        this.applicationType = applicationType;
    }

    public String getApplicationTypeLabel() {
        return applicationTypeLabel;
    }

    public void setApplicationTypeLabel(String applicationTypeLabel) {
        this.applicationTypeLabel = applicationTypeLabel;
    }

    public LocalDateTime getApplicationDate() {
        return applicationDate;
    }

    public void setApplicationDate(LocalDateTime applicationDate) {
        this.applicationDate = applicationDate;
    }

    public long getWaitingDays() {
        if (!canApprove || applicationDate == null) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(applicationDate.toLocalDate(), LocalDate.now()));
    }

    public boolean isOverdue() {
        return canApprove && getWaitingDays() >= 7;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getAmountLabel() {
        return amountLabel;
    }

    public void setAmountLabel(String amountLabel) {
        this.amountLabel = amountLabel;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public void setStatus(ApplicationStatus status) {
        this.status = status;
    }

    public boolean isCanApprove() {
        return canApprove;
    }

    public void setCanApprove(boolean canApprove) {
        this.canApprove = canApprove;
    }

    public boolean isCanWithdraw() {
        return canWithdraw;
    }

    public void setCanWithdraw(boolean canWithdraw) {
        this.canWithdraw = canWithdraw;
    }
}
