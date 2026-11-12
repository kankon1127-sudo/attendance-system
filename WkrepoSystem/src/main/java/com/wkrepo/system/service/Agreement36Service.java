package com.wkrepo.system.service;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 36 協定（労働基準法の時間外・休日労働の上限）チェック。超過しても提出は止めず、警告として表示する。
 * <ul>
 *   <li>休日労働 = 法定休日（日曜日）の実働</li>
 *   <li>時間外労働 = 上記以外の残業（平日の 8 時間超 + 土曜・祝日の実働）</li>
 *   <li>-/欠勤/有給/空 の記録は対象外（月度統計と同じ）</li>
 * </ul>
 */
@Service
public class Agreement36Service {

    static final BigDecimal MONTHLY_OVERTIME_LIMIT = new BigDecimal("45");
    static final BigDecimal MONTHLY_TOTAL_LIMIT = new BigDecimal("100");
    static final BigDecimal AVERAGE_TOTAL_LIMIT = new BigDecimal("80");
    static final BigDecimal YEARLY_OVERTIME_LIMIT = new BigDecimal("360");
    static final BigDecimal YEARLY_OVERTIME_SPECIAL_LIMIT = new BigDecimal("720");
    static final int MAX_MONTHS_OVER_45 = 6;

    private final AttendanceService attendanceService;
    private final int yearStartMonth;

    public Agreement36Service(AttendanceService attendanceService,
                              @Value("${app.agreement36.year-start-month:4}") int yearStartMonth) {
        this.attendanceService = attendanceService;
        this.yearStartMonth = yearStartMonth;
    }

    public Result check(User user, YearMonth target) {
        YearMonth yearStart = yearStartOf(target);
        YearMonth from = target.minusMonths(5).isBefore(yearStart) ? target.minusMonths(5) : yearStart;
        List<Attendance> attendances = attendanceService.getAttendancesByUserAndDateRange(
                user, from.atDay(1), target.atEndOfMonth());
        return evaluate(target, yearStart, aggregate(attendances));
    }

    /** 月ごとの時間外労働・休日労働 */
    Map<YearMonth, MonthlyHours> aggregate(List<Attendance> attendances) {
        Map<YearMonth, MonthlyHours> byMonth = new HashMap<>();
        for (Attendance attendance : attendances) {
            String status = attendance.getAgreement36Status();
            LocalDate date = attendance.getDate();
            if (date == null || status == null || status.isBlank() || "-".equals(status)
                    || AttendanceService.isNonWorkingStatus(status)) {
                continue;
            }
            MonthlyHours hours = byMonth.computeIfAbsent(YearMonth.from(date), MonthlyHours::new);
            // 休日労働時間が計算済みならそれを使う（振休で振り替えた日曜日は 0 になり、時間外として数える）。
            // 計算前の古いデータは日曜日かどうかで判定する
            boolean holidayWork = attendance.getHolidayHours() != null
                    ? attendance.getHolidayHours().compareTo(BigDecimal.ZERO) > 0
                    : date.getDayOfWeek() == DayOfWeek.SUNDAY;
            if (holidayWork) {
                hours.holidayWork = hours.holidayWork.add(valueOrZero(attendance.getWorkHours()));
            } else {
                hours.overtime = hours.overtime.add(valueOrZero(attendance.getOvertimeHours()));
            }
        }
        return byMonth;
    }

    Result evaluate(YearMonth target, YearMonth yearStart, Map<YearMonth, MonthlyHours> byMonth) {
        MonthlyHours current = byMonth.getOrDefault(target, new MonthlyHours(target));
        List<Alert> alerts = new ArrayList<>();

        // 月 45 時間（原則の上限）
        if (current.overtime.compareTo(MONTHLY_OVERTIME_LIMIT) > 0) {
            alerts.add(Alert.warning("agreement36.monthlyOvertime", format(current.overtime)));
        }
        // 時間外 + 休日 月 100 時間未満
        if (current.total().compareTo(MONTHLY_TOTAL_LIMIT) >= 0) {
            alerts.add(Alert.error("agreement36.monthlyTotal", format(current.total())));
        }
        // 2〜6 か月平均 80 時間以内（超過した期間のうち平均が最も高いものだけ表示）
        int worstMonths = 0;
        BigDecimal worstAverage = AVERAGE_TOTAL_LIMIT;
        for (int months = 2; months <= 6; months++) {
            BigDecimal sum = BigDecimal.ZERO;
            for (int i = 0; i < months; i++) {
                sum = sum.add(byMonth.getOrDefault(target.minusMonths(i), new MonthlyHours(target)).total());
            }
            BigDecimal average = sum.divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
            if (average.compareTo(worstAverage) > 0) {
                worstMonths = months;
                worstAverage = average;
            }
        }
        if (worstMonths > 0) {
            alerts.add(Alert.error("agreement36.averageTotal", worstMonths, format(worstAverage)));
        }

        // 年度（yearStart 〜 target）
        BigDecimal yearlyOvertime = BigDecimal.ZERO;
        int monthsOver45 = 0;
        for (YearMonth month = yearStart; !month.isAfter(target); month = month.plusMonths(1)) {
            BigDecimal overtime = byMonth.getOrDefault(month, new MonthlyHours(month)).overtime;
            yearlyOvertime = yearlyOvertime.add(overtime);
            if (overtime.compareTo(MONTHLY_OVERTIME_LIMIT) > 0) {
                monthsOver45++;
            }
        }
        if (yearlyOvertime.compareTo(YEARLY_OVERTIME_SPECIAL_LIMIT) > 0) {
            alerts.add(Alert.error("agreement36.yearlyOvertimeSpecial", format(yearlyOvertime)));
        } else if (yearlyOvertime.compareTo(YEARLY_OVERTIME_LIMIT) > 0) {
            alerts.add(Alert.warning("agreement36.yearlyOvertime", format(yearlyOvertime)));
        }
        if (monthsOver45 > MAX_MONTHS_OVER_45) {
            alerts.add(Alert.error("agreement36.monthsOver45", monthsOver45));
        }

        return new Result(current.overtime, current.holidayWork, yearlyOvertime, monthsOver45, alerts);
    }

    YearMonth yearStartOf(YearMonth target) {
        int startYear = target.getMonthValue() >= yearStartMonth ? target.getYear() : target.getYear() - 1;
        return YearMonth.of(startYear, yearStartMonth);
    }

    private static BigDecimal valueOrZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String format(BigDecimal hours) {
        return hours.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static final class MonthlyHours {
        final YearMonth month;
        BigDecimal overtime = BigDecimal.ZERO;
        BigDecimal holidayWork = BigDecimal.ZERO;

        MonthlyHours(YearMonth month) {
            this.month = month;
        }

        BigDecimal total() {
            return overtime.add(holidayWork);
        }
    }

    /** error = 法定上限の違反、warning = 原則の上限超過（特別条項が必要） */
    public record Alert(boolean error, String messageKey, Object[] args) {
        static Alert warning(String messageKey, Object... args) {
            return new Alert(false, messageKey, args);
        }

        static Alert error(String messageKey, Object... args) {
            return new Alert(true, messageKey, args);
        }
    }

    public record Result(BigDecimal monthlyOvertime, BigDecimal monthlyHolidayWork,
                         BigDecimal yearlyOvertime, int monthsOver45, List<Alert> alerts) {
        public boolean hasAlerts() {
            return !alerts.isEmpty();
        }

        public boolean hasErrors() {
            return alerts.stream().anyMatch(Alert::error);
        }
    }
}
