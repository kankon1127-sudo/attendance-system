package com.wkrepo.system.enums;

public enum ApplicationStatus {
    PENDING("申請中"),
    APPROVED("承認済"),
    REJECTED("拒否"),
    DRAFT("下書き"),
    SUBMITTED("提出済"),
    MANAGER_APPROVED("リーダー承認済"),
    FINALIZED("総務確認済");

    private final String displayName;

    ApplicationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
