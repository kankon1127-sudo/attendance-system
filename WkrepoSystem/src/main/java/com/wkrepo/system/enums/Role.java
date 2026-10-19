package com.wkrepo.system.enums;

public enum Role {
    GENERAL("一般"),
    MANAGER("部長"),
    SOUMU("総務"),
    ADMIN("管理者");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static String displayName(String role) {
        if (role == null) {
            return GENERAL.displayName;
        }
        return switch (role) {
            case "ADMIN" -> ADMIN.displayName;
            case "MANAGER" -> MANAGER.displayName;
            case "SOUMU" -> SOUMU.displayName;
            default -> GENERAL.displayName;
        };
    }
}
