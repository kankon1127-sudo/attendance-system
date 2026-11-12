package com.wkrepo.system.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Table(name = "attendances")
public class Attendance {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Column(nullable = false)
    private LocalDate date;
    
    @Column(name = "start_time")
    private LocalTime startTime;
    
    @Column(name = "end_time")
    private LocalTime endTime;
    
    @Column(name = "break_time")
    private LocalTime breakTime;
    
    @Column(name = "work_hours", precision = 5, scale = 2)
    private BigDecimal workHours;
    
    @Column(name = "overtime_hours", precision = 5, scale = 2)
    private BigDecimal overtimeHours;
    
    /** 退勤が翌日（日をまたぐ勤務）。false の場合、退勤は出勤より後でなければならない */
    @Column(name = "end_next_day")
    private Boolean endNextDay;

    /** 深夜労働時間（22:00〜翌 5:00 に含まれる勤務時間。休憩は差し引かない） */
    @Column(name = "night_hours", precision = 5, scale = 2)
    private BigDecimal nightHours;

    /** 休日労働時間（法定休日＝日曜日の実働時間） */
    @Column(name = "holiday_hours", precision = 5, scale = 2)
    private BigDecimal holidayHours;

    @Column(name = "agreement_36_status", length = 20)
    private String agreement36Status;

    /** 振休・代休：対応する休日出勤の日（その日の勤怠は本人の休日の出勤であること） */
    @Column(name = "substitute_date")
    private java.time.LocalDate substituteDate;

    /** DB から読み込んだ時点の substituteDate（変更・解除されたとき、元の休日出勤日を再計算するため） */
    @Transient
    private java.time.LocalDate loadedSubstituteDate;

    @PostLoad
    protected void rememberLoadedValues() {
        loadedSubstituteDate = substituteDate;
    }

    public java.time.LocalDate getSubstituteDate() {
        return substituteDate;
    }

    public void setSubstituteDate(java.time.LocalDate substituteDate) {
        this.substituteDate = substituteDate;
    }

    public java.time.LocalDate getLoadedSubstituteDate() {
        return loadedSubstituteDate;
    }

    public boolean isEndNextDay() {
        return Boolean.TRUE.equals(endNextDay);
    }

    public Boolean getEndNextDay() {
        return endNextDay;
    }

    public void setEndNextDay(Boolean endNextDay) {
        this.endNextDay = endNextDay;
    }

    public BigDecimal getNightHours() {
        return nightHours;
    }

    public void setNightHours(BigDecimal nightHours) {
        this.nightHours = nightHours;
    }

    public BigDecimal getHolidayHours() {
        return holidayHours;
    }

    public void setHolidayHours(BigDecimal holidayHours) {
        this.holidayHours = holidayHours;
    }
    
    @Column(length = 500)
    private String remarks;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // Constructors
    public Attendance() {}
    
    public Attendance(User user, LocalDate date) {
        this.user = user;
        this.date = date;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public User getUser() {
        return user;
    }
    
    public void setUser(User user) {
        this.user = user;
    }
    
    public LocalDate getDate() {
        return date;
    }
    
    public void setDate(LocalDate date) {
        this.date = date;
    }
    
    public LocalTime getStartTime() {
        return startTime;
    }
    
    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }
    
    public LocalTime getEndTime() {
        return endTime;
    }
    
    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }
    
    public LocalTime getBreakTime() {
        return breakTime;
    }
    
    public void setBreakTime(LocalTime breakTime) {
        this.breakTime = breakTime;
    }
    
    public BigDecimal getWorkHours() {
        return workHours;
    }
    
    public void setWorkHours(BigDecimal workHours) {
        this.workHours = workHours;
    }
    
    public BigDecimal getOvertimeHours() {
        return overtimeHours;
    }
    
    public void setOvertimeHours(BigDecimal overtimeHours) {
        this.overtimeHours = overtimeHours;
    }
    
    public String getAgreement36Status() {
        return agreement36Status;
    }
    
    public void setAgreement36Status(String agreement36Status) {
        this.agreement36Status = agreement36Status;
    }
    
    public String getRemarks() {
        return remarks;
    }
    
    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
