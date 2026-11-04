package com.wkrepo.system;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.User;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;

/** テストデータ生成ユーティリティ */
public final class TestData {

    private TestData() {}

    public static User user(long id, String role, String department) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setName("User" + id);
        user.setEmployeeId("EMP" + id);
        user.setRole(role);
        user.setDepartment(department);
        user.setStatus("ACTIVE");
        return user;
    }

    public static Attendance attendance(User user, String date, String status,
                                        String start, String end, String breakTime) {
        Attendance attendance = new Attendance(user, LocalDate.parse(date));
        attendance.setAgreement36Status(status);
        attendance.setStartTime(start == null ? null : LocalTime.parse(start));
        attendance.setEndTime(end == null ? null : LocalTime.parse(end));
        attendance.setBreakTime(breakTime == null ? null : LocalTime.parse(breakTime));
        return attendance;
    }

    /** 本番と同じ messages*.properties を読む Messages（画面の言語は日本語） */
    public static Messages messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        return new Messages(source);
    }

    public static Attendance attendanceWithHours(User user, String date, String status,
                                                 String workHours, String overtimeHours) {
        Attendance attendance = new Attendance(user, LocalDate.parse(date));
        attendance.setAgreement36Status(status);
        attendance.setWorkHours(new BigDecimal(workHours));
        attendance.setOvertimeHours(new BigDecimal(overtimeHours));
        return attendance;
    }
}
