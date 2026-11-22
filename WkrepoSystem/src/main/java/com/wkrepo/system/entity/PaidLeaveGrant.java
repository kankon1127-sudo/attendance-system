package com.wkrepo.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 年休の付与（1 回の付与 = 1 行）。付与日から 2 年で時効になる（expiry_date まで有効）。
 * 取得は古い付与から順に充てる（PaidLeaveLedger）。
 */
@Entity
@Table(name = "paid_leave_grants", indexes = @Index(columnList = "user_id, grant_date"))
public class PaidLeaveGrant {

    /** 自動付与（入社日基準・労基法 39 条） */
    public static final String SOURCE_AUTO = "AUTO";
    /** 総務・管理者による手動付与（特別付与・調整） */
    public static final String SOURCE_MANUAL = "MANUAL";
    /** 旧方式（年ごとの付与日数）から移行したもの */
    public static final String SOURCE_MIGRATED = "MIGRATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "grant_date", nullable = false)
    private LocalDate grantDate;

    /** 有効期限（この日まで使える）。通常は付与日の 2 年後の前日 */
    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "granted_days", nullable = false, precision = 5, scale = 1)
    private BigDecimal grantedDays;

    @Column(nullable = false, length = 20)
    private String source;

    /** 自動付与の判定に使った出勤率（%）。勤怠データがない期間は null */
    @Column(name = "attendance_rate", precision = 5, scale = 1)
    private BigDecimal attendanceRate;

    @Column(length = 200)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public LocalDate getGrantDate() {
        return grantDate;
    }

    public void setGrantDate(LocalDate grantDate) {
        this.grantDate = grantDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public BigDecimal getGrantedDays() {
        return grantedDays;
    }

    public void setGrantedDays(BigDecimal grantedDays) {
        this.grantedDays = grantedDays;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public BigDecimal getAttendanceRate() {
        return attendanceRate;
    }

    public void setAttendanceRate(BigDecimal attendanceRate) {
        this.attendanceRate = attendanceRate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** 指定日に有効か（付与日〜有効期限） */
    public boolean isValidOn(LocalDate date) {
        return date != null && !date.isBefore(grantDate) && !date.isAfter(expiryDate);
    }
}
