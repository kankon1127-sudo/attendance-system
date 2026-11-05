package com.wkrepo.system.entity;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * 国民の祝日・休日（振替休日、国民の休日を含む）。
 * 初回起動時に内閣府の CSV から取り込み、管理者が祝日管理画面で追加・削除する。
 */
@Entity
@Table(name = "holidays")
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "holiday_date", nullable = false, unique = true)
    private LocalDate date;

    @Column(nullable = false, length = 50)
    private String name;

    public Holiday() {}

    public Holiday(LocalDate date, String name) {
        this.date = date;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
