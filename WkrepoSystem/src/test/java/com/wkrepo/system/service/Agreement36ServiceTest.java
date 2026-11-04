package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.User;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Agreement36ServiceTest {

    private final Agreement36Service service =
            new Agreement36Service(Mockito.mock(AttendanceService.class), 4);
    private final User user = TestData.user(1L, "GENERAL", "开发部");
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    // ---------- 集計：時間外労働と休日労働の区別 ----------

    @Test
    void sundayIsHolidayWorkAndSaturdayIsOvertime() {
        Map<YearMonth, Agreement36Service.MonthlyHours> byMonth = service.aggregate(List.of(
                TestData.attendanceWithHours(user, "2026-11-02", "出社", "10.00", "2.00"), // 平日：残業 2
                TestData.attendanceWithHours(user, "2026-11-07", "出社", "5.00", "5.00"),  // 土曜：時間外 5
                TestData.attendanceWithHours(user, "2026-11-03", "在宅", "3.00", "3.00"),  // 祝日：時間外 3
                TestData.attendanceWithHours(user, "2026-11-01", "出社", "6.00", "6.00"),  // 日曜：休日労働 6
                TestData.attendanceWithHours(user, "2026-11-04", "有給", "8.00", "8.00"),  // 対象外
                TestData.attendanceWithHours(user, "2026-11-05", "欠勤", "8.00", "8.00"))); // 対象外

        Agreement36Service.MonthlyHours hours = byMonth.get(NOV);
        assertThat(hours.overtime).isEqualByComparingTo("10.00");
        assertThat(hours.holidayWork).isEqualByComparingTo("6.00");
    }

    // ---------- 月度 ----------

    @Test
    void noAlertsWithinLimits() {
        Agreement36Service.Result result = evaluate(Map.of(NOV, hours(NOV, "45", "10")));

        assertThat(result.hasAlerts()).isFalse();
    }

    @Test
    void over45HoursIsWarningOnly() {
        Agreement36Service.Result result = evaluate(Map.of(NOV, hours(NOV, "45.5", "0")));

        assertThat(keys(result)).containsExactly("agreement36.monthlyOvertime");
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void hundredHoursIncludingHolidayWorkIsError() {
        Agreement36Service.Result result = evaluate(Map.of(NOV, hours(NOV, "40", "60")));

        assertThat(keys(result)).contains("agreement36.monthlyTotal");
        assertThat(keys(result)).doesNotContain("agreement36.monthlyOvertime");
        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void averageOver80ReportsWorstPeriodOnce() {
        Map<YearMonth, Agreement36Service.MonthlyHours> byMonth = new HashMap<>();
        byMonth.put(NOV, hours(NOV, "60", "25"));                                  // 85
        byMonth.put(NOV.minusMonths(1), hours(NOV.minusMonths(1), "60", "20"));   // 80 → 2 か月平均 82.5

        Agreement36Service.Result result = evaluate(byMonth);

        List<Agreement36Service.Alert> averages = result.alerts().stream()
                .filter(alert -> alert.messageKey().equals("agreement36.averageTotal")).toList();
        assertThat(averages).hasSize(1);
        assertThat(averages.get(0).args()).containsExactly(2, "82.50");
        assertThat(averages.get(0).error()).isTrue();
    }

    @Test
    void averageExactly80IsAllowed() {
        Map<YearMonth, Agreement36Service.MonthlyHours> byMonth = new HashMap<>();
        byMonth.put(NOV, hours(NOV, "45", "35"));
        byMonth.put(NOV.minusMonths(1), hours(NOV.minusMonths(1), "45", "35"));

        assertThat(keys(evaluate(byMonth))).doesNotContain("agreement36.averageTotal");
    }

    // ---------- 年度（4 月起算） ----------

    @Test
    void yearStartsInApril() {
        assertThat(service.yearStartOf(YearMonth.of(2026, 11))).isEqualTo(YearMonth.of(2026, 4));
        assertThat(service.yearStartOf(YearMonth.of(2027, 2))).isEqualTo(YearMonth.of(2026, 4));
        assertThat(service.yearStartOf(YearMonth.of(2026, 4))).isEqualTo(YearMonth.of(2026, 4));
    }

    @Test
    void yearlyOvertimeOver360IsWarning() {
        // 4〜11 月の 8 か月 × 45h = 360h、11 月だけ 46h → 361h
        Map<YearMonth, Agreement36Service.MonthlyHours> byMonth = monthsFromApril("45");
        byMonth.put(NOV, hours(NOV, "46", "0"));

        Agreement36Service.Result result = evaluate(byMonth);

        assertThat(keys(result)).contains("agreement36.yearlyOvertime");
        assertThat(result.yearlyOvertime()).isEqualByComparingTo("361");
    }

    @Test
    void yearlyOvertimeOver720AndMoreThanSixMonthsOver45AreErrors() {
        // 4〜11 月の 8 か月 × 91h = 728h、45h 超の月が 8 回
        Map<YearMonth, Agreement36Service.MonthlyHours> byMonth = monthsFromApril("91");

        Agreement36Service.Result result = evaluate(byMonth);

        assertThat(keys(result)).contains("agreement36.yearlyOvertimeSpecial", "agreement36.monthsOver45");
        assertThat(keys(result)).doesNotContain("agreement36.yearlyOvertime");
        assertThat(result.monthsOver45()).isEqualTo(8);
    }

    @Test
    void previousYearDoesNotCount() {
        // 3 月（前年度）は 45h 超でも今年度の回数に入らない
        Map<YearMonth, Agreement36Service.MonthlyHours> byMonth = new HashMap<>();
        YearMonth march = YearMonth.of(2026, 3);
        byMonth.put(march, hours(march, "60", "0"));

        assertThat(evaluate(byMonth).monthsOver45()).isZero();
    }

    private Agreement36Service.Result evaluate(Map<YearMonth, Agreement36Service.MonthlyHours> byMonth) {
        return service.evaluate(NOV, service.yearStartOf(NOV), byMonth);
    }

    private Map<YearMonth, Agreement36Service.MonthlyHours> monthsFromApril(String overtime) {
        Map<YearMonth, Agreement36Service.MonthlyHours> byMonth = new HashMap<>();
        for (YearMonth month = YearMonth.of(2026, 4); !month.isAfter(NOV); month = month.plusMonths(1)) {
            byMonth.put(month, hours(month, overtime, "0"));
        }
        return byMonth;
    }

    private Agreement36Service.MonthlyHours hours(YearMonth month, String overtime, String holidayWork) {
        Agreement36Service.MonthlyHours hours = new Agreement36Service.MonthlyHours(month);
        hours.overtime = new BigDecimal(overtime);
        hours.holidayWork = new BigDecimal(holidayWork);
        return hours;
    }

    private List<String> keys(Agreement36Service.Result result) {
        return result.alerts().stream().map(Agreement36Service.Alert::messageKey).toList();
    }
}
