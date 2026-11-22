package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 法定の付与日数表（通常・比例付与）、付与日と算定期間、時効 */
class LeaveGrantPolicyTest {

    @Test
    void fullTimeScheduleFollowsStatutoryTable() {
        User user = user("2020-04-01", null, null);

        List<LeaveGrantPolicy.Schedule> schedules = LeaveGrantPolicy.schedulesUntil(user, LocalDate.of(2026, 11, 22));

        assertThat(schedules).extracting(LeaveGrantPolicy.Schedule::grantDate).containsExactly(
                LocalDate.of(2020, 10, 1), LocalDate.of(2021, 10, 1), LocalDate.of(2022, 10, 1),
                LocalDate.of(2023, 10, 1), LocalDate.of(2024, 10, 1), LocalDate.of(2025, 10, 1),
                LocalDate.of(2026, 10, 1));
        assertThat(schedules).extracting(s -> s.days().intValue()).containsExactly(10, 11, 12, 14, 16, 18, 20);
        // 6 年 6 か月以降は 20 日のまま
        assertThat(LeaveGrantPolicy.nextSchedule(user, LocalDate.of(2026, 11, 22)).days()).isEqualByComparingTo("20");
    }

    @Test
    void attendancePeriodIsFromPreviousGrant() {
        List<LeaveGrantPolicy.Schedule> schedules =
                LeaveGrantPolicy.schedulesUntil(user("2024-04-01", null, null), LocalDate.of(2025, 10, 1));

        assertThat(schedules.get(0).periodStart()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(schedules.get(0).periodEnd()).isEqualTo(LocalDate.of(2024, 9, 30));
        assertThat(schedules.get(1).periodStart()).isEqualTo(LocalDate.of(2024, 10, 1));
        assertThat(schedules.get(1).periodEnd()).isEqualTo(LocalDate.of(2025, 9, 30));
    }

    @Test
    void nextScheduleBeforeFirstGrant() {
        LeaveGrantPolicy.Schedule next = LeaveGrantPolicy.nextSchedule(user("2026-08-01", null, null),
                LocalDate.of(2026, 11, 22));

        assertThat(next.grantDate()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(next.days()).isEqualByComparingTo("10");
        assertThat(next.periodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    void noHireDateMeansNoSchedule() {
        User user = user(null, null, null);

        assertThat(LeaveGrantPolicy.schedulesUntil(user, LocalDate.of(2026, 11, 22))).isEmpty();
        assertThat(LeaveGrantPolicy.nextSchedule(user, LocalDate.of(2026, 11, 22))).isNull();
    }

    @Test
    void proportionalGrantForShortWeeks() {
        User threeDays = user("2020-04-01", 3, "20");
        assertThat(LeaveGrantPolicy.isProportional(threeDays)).isTrue();
        assertThat(LeaveGrantPolicy.schedulesUntil(threeDays, LocalDate.of(2026, 11, 22)))
                .extracting(s -> s.days().intValue()).containsExactly(5, 6, 6, 8, 9, 10, 11);

        assertThat(LeaveGrantPolicy.daysFor(user("2020-04-01", 1, "6"), 0)).isEqualByComparingTo("1");
        assertThat(LeaveGrantPolicy.daysFor(user("2020-04-01", 4, "29.5"), 6)).isEqualByComparingTo("15");
    }

    @Test
    void thirtyHoursOrUnknownHoursIsNotProportional() {
        assertThat(LeaveGrantPolicy.isProportional(user("2020-04-01", 4, "30"))).isFalse();
        assertThat(LeaveGrantPolicy.isProportional(user("2020-04-01", 4, null))).isFalse();
        assertThat(LeaveGrantPolicy.isProportional(user("2020-04-01", null, "20"))).isFalse();
        assertThat(LeaveGrantPolicy.daysFor(user("2020-04-01", 4, "30"), 0)).isEqualByComparingTo("10");
    }

    @Test
    void grantExpiresAfterTwoYears() {
        assertThat(LeaveGrantPolicy.expiryOf(LocalDate.of(2026, 10, 1))).isEqualTo(LocalDate.of(2028, 9, 30));
    }

    private static User user(String hireDate, Integer weeklyDays, String weeklyHours) {
        User user = TestData.user(3L, "GENERAL", "開発部");
        user.setHireDate(hireDate == null ? null : LocalDate.parse(hireDate));
        user.setWeeklyWorkDays(weeklyDays);
        user.setWeeklyWorkHours(weeklyHours == null ? null : new BigDecimal(weeklyHours));
        return user;
    }
}
