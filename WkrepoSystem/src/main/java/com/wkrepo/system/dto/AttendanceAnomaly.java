package com.wkrepo.system.dto;

import java.time.LocalDate;

public class AttendanceAnomaly {

    private final LocalDate date;
    private final String messageKey;

    public AttendanceAnomaly(LocalDate date, String messageKey) {
        this.date = date;
        this.messageKey = messageKey;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
