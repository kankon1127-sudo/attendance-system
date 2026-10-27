package com.wkrepo.system.dto;

import java.time.LocalDateTime;

public class ApprovalHistoryEntry {

    private final String previousStatus;
    private final String nextStatus;
    private final String actorName;
    private final LocalDateTime actionAt;

    public ApprovalHistoryEntry(String previousStatus, String nextStatus, String actorName, LocalDateTime actionAt) {
        this.previousStatus = previousStatus;
        this.nextStatus = nextStatus;
        this.actorName = actorName;
        this.actionAt = actionAt;
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
}
