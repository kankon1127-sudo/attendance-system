package com.wkrepo.system.entity;

import jakarta.persistence.*;
import java.time.LocalTime;

@Entity
@Table(name = "default_attendance_settings")
public class DefaultAttendanceSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "default_start_time")
    private LocalTime defaultStartTime;

    @Column(name = "default_end_time")
    private LocalTime defaultEndTime;

    @Column(name = "default_break_minutes")
    private Integer defaultBreakMinutes;

    @Column(name = "default_agreement_36_status", length = 50)
    private String defaultAgreement36Status;

    @Column(name = "default_approver", length = 100)
    private String defaultApprover;

    // Constructors
    public DefaultAttendanceSetting() {}

    public DefaultAttendanceSetting(User user) {
        this.user = user;
        // 设置默认值
        this.defaultStartTime = LocalTime.of(8, 30);
        this.defaultEndTime = LocalTime.of(18, 0);
        this.defaultBreakMinutes = 60;
        this.defaultAgreement36Status = "在宅";
        this.defaultApprover = "";
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

    public LocalTime getDefaultStartTime() {
        return defaultStartTime;
    }

    public void setDefaultStartTime(LocalTime defaultStartTime) {
        this.defaultStartTime = defaultStartTime;
    }

    public LocalTime getDefaultEndTime() {
        return defaultEndTime;
    }

    public void setDefaultEndTime(LocalTime defaultEndTime) {
        this.defaultEndTime = defaultEndTime;
    }

    public Integer getDefaultBreakMinutes() {
        return defaultBreakMinutes;
    }

    public void setDefaultBreakMinutes(Integer defaultBreakMinutes) {
        this.defaultBreakMinutes = defaultBreakMinutes;
    }

    public String getDefaultAgreement36Status() {
        return defaultAgreement36Status;
    }

    public void setDefaultAgreement36Status(String defaultAgreement36Status) {
        this.defaultAgreement36Status = defaultAgreement36Status;
    }

    public String getDefaultApprover() {
        return defaultApprover;
    }

    public void setDefaultApprover(String defaultApprover) {
        this.defaultApprover = defaultApprover;
    }
}