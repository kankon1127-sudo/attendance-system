package com.wkrepo.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "paid_leave_entitlements",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "entitlement_year"}))
public class PaidLeaveEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "entitlement_year", nullable = false)
    private int year;

    @Column(name = "granted_days", nullable = false, precision = 5, scale = 1)
    private BigDecimal grantedDays;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public BigDecimal getGrantedDays() {
        return grantedDays;
    }

    public void setGrantedDays(BigDecimal grantedDays) {
        this.grantedDays = grantedDays;
    }
}
