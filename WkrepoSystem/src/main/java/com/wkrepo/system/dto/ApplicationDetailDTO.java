package com.wkrepo.system.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class ApplicationDetailDTO {

    private Long id;
    private String applicationType;
    private String applicationTypeLabel;
    private String applicantName;
    private String applicantDepartment;
    private String status;
    private String statusDisplayName;
    private LocalDateTime applicationDate;
    private boolean canApprove;
    private String approveButtonLabel;

    private LocalDate expenseDate;
    private String travelType;
    private String transportation;
    private String route;
    private String travelSummary;
    private BigDecimal travelAmount;

    private LocalDate generalDate;
    private String expenseType;
    private String generalSummary;
    private BigDecimal generalAmount;
    private String generalRemarks;

    private LocalDate startDate;
    private LocalDate endDate;
    private BigDecimal leaveDays;
    private String leaveType;
    private String leaveRemarks;

    private Integer reportYear;
    private Integer reportMonth;
    private String workContent;
    private Long approverId;
    private String approverName;
    private MonthlySummaryDTO monthlySummary;
    private List<AttendanceDailyItem> attendanceList;
    private List<ApprovalHistoryEntry> approvalHistory;

    public static class AttendanceDailyItem {
        private String date;
        private String dateLabel;
        private String dayOfWeek;
        private boolean saturday;
        private boolean sunday;
        private boolean holiday;
        private String startTime;
        private String endTime;
        private String breakTime;
        private BigDecimal workHours;
        private BigDecimal overtimeHours;
        private String status;
        private String remarks;

        public AttendanceDailyItem() {}

        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getDateLabel() { return dateLabel; }
        public void setDateLabel(String dateLabel) { this.dateLabel = dateLabel; }
        public String getDayOfWeek() { return dayOfWeek; }
        public void setDayOfWeek(String dayOfWeek) { this.dayOfWeek = dayOfWeek; }
        public boolean isSaturday() { return saturday; }
        public void setSaturday(boolean saturday) { this.saturday = saturday; }
        public boolean isSunday() { return sunday; }
        public void setSunday(boolean sunday) { this.sunday = sunday; }
        public boolean isHoliday() { return holiday; }
        public void setHoliday(boolean holiday) { this.holiday = holiday; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }
        public String getBreakTime() { return breakTime; }
        public void setBreakTime(String breakTime) { this.breakTime = breakTime; }
        public BigDecimal getWorkHours() { return workHours; }
        public void setWorkHours(BigDecimal workHours) { this.workHours = workHours; }
        public BigDecimal getOvertimeHours() { return overtimeHours; }
        public void setOvertimeHours(BigDecimal overtimeHours) { this.overtimeHours = overtimeHours; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getRemarks() { return remarks; }
        public void setRemarks(String remarks) { this.remarks = remarks; }
    }

    public ApplicationDetailDTO() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusDisplayName() {
        return statusDisplayName;
    }

    public void setStatusDisplayName(String statusDisplayName) {
        this.statusDisplayName = statusDisplayName;
    }

    public LocalDateTime getApplicationDate() {
        return applicationDate;
    }

    public void setApplicationDate(LocalDateTime applicationDate) {
        this.applicationDate = applicationDate;
    }

    public boolean isCanApprove() {
        return canApprove;
    }

    public void setCanApprove(boolean canApprove) {
        this.canApprove = canApprove;
    }

    public String getApproveButtonLabel() {
        return approveButtonLabel;
    }

    public void setApproveButtonLabel(String approveButtonLabel) {
        this.approveButtonLabel = approveButtonLabel;
    }

    public LocalDate getExpenseDate() {
        return expenseDate;
    }

    public void setExpenseDate(LocalDate expenseDate) {
        this.expenseDate = expenseDate;
    }

    public String getTravelType() {
        return travelType;
    }

    public void setTravelType(String travelType) {
        this.travelType = travelType;
    }

    public String getTransportation() {
        return transportation;
    }

    public void setTransportation(String transportation) {
        this.transportation = transportation;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getTravelSummary() {
        return travelSummary;
    }

    public void setTravelSummary(String travelSummary) {
        this.travelSummary = travelSummary;
    }

    public BigDecimal getTravelAmount() {
        return travelAmount;
    }

    public void setTravelAmount(BigDecimal travelAmount) {
        this.travelAmount = travelAmount;
    }

    public LocalDate getGeneralDate() {
        return generalDate;
    }

    public void setGeneralDate(LocalDate generalDate) {
        this.generalDate = generalDate;
    }

    public String getExpenseType() {
        return expenseType;
    }

    public void setExpenseType(String expenseType) {
        this.expenseType = expenseType;
    }

    public String getGeneralSummary() {
        return generalSummary;
    }

    public void setGeneralSummary(String generalSummary) {
        this.generalSummary = generalSummary;
    }

    public BigDecimal getGeneralAmount() {
        return generalAmount;
    }

    public void setGeneralAmount(BigDecimal generalAmount) {
        this.generalAmount = generalAmount;
    }

    public String getGeneralRemarks() {
        return generalRemarks;
    }

    public void setGeneralRemarks(String generalRemarks) {
        this.generalRemarks = generalRemarks;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getLeaveDays() {
        return leaveDays;
    }

    public void setLeaveDays(BigDecimal leaveDays) {
        this.leaveDays = leaveDays;
    }

    public String getLeaveType() {
        return leaveType;
    }

    public void setLeaveType(String leaveType) {
        this.leaveType = leaveType;
    }

    public String getLeaveRemarks() {
        return leaveRemarks;
    }

    public void setLeaveRemarks(String leaveRemarks) {
        this.leaveRemarks = leaveRemarks;
    }

    public Integer getReportYear() {
        return reportYear;
    }

    public void setReportYear(Integer reportYear) {
        this.reportYear = reportYear;
    }

    public Integer getReportMonth() {
        return reportMonth;
    }

    public void setReportMonth(Integer reportMonth) {
        this.reportMonth = reportMonth;
    }

    public String getWorkContent() {
        return workContent;
    }

    public void setWorkContent(String workContent) {
        this.workContent = workContent;
    }

    public Long getApproverId() {
        return approverId;
    }

    public void setApproverId(Long approverId) {
        this.approverId = approverId;
    }

    public String getApproverName() {
        return approverName;
    }

    public void setApproverName(String approverName) {
        this.approverName = approverName;
    }

    public MonthlySummaryDTO getMonthlySummary() {
        return monthlySummary;
    }

    public void setMonthlySummary(MonthlySummaryDTO monthlySummary) {
        this.monthlySummary = monthlySummary;
    }

    public List<AttendanceDailyItem> getAttendanceList() {
        return attendanceList;
    }

    public void setAttendanceList(List<AttendanceDailyItem> attendanceList) {
        this.attendanceList = attendanceList;
    }

    public List<ApprovalHistoryEntry> getApprovalHistory() {
        return approvalHistory;
    }

    public void setApprovalHistory(List<ApprovalHistoryEntry> approvalHistory) {
        this.approvalHistory = approvalHistory;
    }
}
