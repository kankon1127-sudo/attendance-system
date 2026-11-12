package com.wkrepo.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * システム内通知。本文は保存せず、メッセージキーと引数を保存して表示時に画面の言語で組み立てる。
 * dedupe_key が同じ通知は同じ人に 2 回作らない（定期リマインドの重複防止）。
 */
@Entity
@Table(name = "notifications",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "dedupe_key"}),
        indexes = @Index(columnList = "user_id, read_at"))
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 受信者 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 種類（MONTHLY_UNSUBMITTED / APPROVAL_OVERDUE / AGREEMENT36 など。アイコンの切り替えに使う） */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    /** メッセージの引数（JSON 配列） */
    @Column(name = "args_json", length = 1000)
    private String argsJson;

    /** 開いたときの遷移先（アプリ内のパス） */
    @Column(length = 255)
    private String link;

    @Column(name = "dedupe_key", length = 150)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public String getArgsJson() {
        return argsJson;
    }

    public void setArgsJson(String argsJson) {
        this.argsJson = argsJson;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public void setReadAt(LocalDateTime readAt) {
        this.readAt = readAt;
    }

    public boolean isRead() {
        return readAt != null;
    }
}
