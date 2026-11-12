package com.wkrepo.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 部署マスタ。users.department には部署名を保存し、選択肢と入力チェックはこのマスタを使う。
 * 部署名を変更すると、所属ユーザーの users.department もまとめて変更する（DepartmentService.rename）。
 */
@Entity
@Table(name = "departments")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /** 表示順（小さい順） */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** false の部署は新規の選択肢に出さない（所属ユーザーはそのまま） */
    @Column(nullable = false)
    private boolean active = true;

    public Department() {
    }

    public Department(String name, int sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
