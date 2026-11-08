package com.wkrepo.system.dto;

import java.time.LocalDateTime;

public class ApprovalHistoryEntry {

    private final String previousStatus;
    private final String nextStatus;
    private final String actorName;
    private final LocalDateTime actionAt;
    private final String comment;

    public ApprovalHistoryEntry(String previousStatus, String nextStatus, String actorName, LocalDateTime actionAt,
                                String comment) {
        this.previousStatus = previousStatus;
        this.nextStatus = nextStatus;
        this.actorName = actorName;
        this.actionAt = actionAt;
        this.comment = comment;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public String getNextStatus() {
        return nextStatus;
    }

    public String getActorName() {
        return actorName;
    }

    public LocalDateTime getActionAt() {
        return actionAt;
    }

    /** 承認者のコメント（拒否理由など）。なければ null */
    public String getComment() {
        return comment;
    }
}
