package com.wkrepo.system.service;

import com.wkrepo.system.entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 年休の法定付与ルール（労働基準法 39 条）。入社日を基準に、6 か月後から毎年付与する。
 * <ul>
 *   <li>通常：6 か月 10 日、1 年 6 か月 11 日、2 年 6 か月 12 日、3 年 6 か月 14 日、4 年 6 か月 16 日、
 *       5 年 6 か月 18 日、6 年 6 か月以降 20 日</li>
 *   <li>比例付与：週の所定労働日数が 4 日以下かつ週 30 時間未満の人は、日数に応じた表で付与</li>
 *   <li>時効：付与日から 2 年（有効期限 = 付与日の 2 年後の前日）</li>
 *   <li>出勤率 8 割以上が付与の条件（判定は LeaveGrantService）</li>
 * </ul>
 */
public final class LeaveGrantPolicy {

    private static final int[] FULL_TIME = {10, 11, 12, 14, 16, 18, 20};
    /** 比例付与：週の所定労働日数 1〜4 日 */
    private static final int[][] PROPORTIONAL = {
            {1, 2, 2, 2, 3, 3, 3},   // 週 1 日
            {3, 4, 4, 5, 6, 6, 7},   // 週 2 日
            {5, 6, 6, 8, 9, 10, 11}, // 週 3 日
            {7, 8, 9, 10, 12, 13, 15} // 週 4 日
    };
    static final BigDecimal PROPORTIONAL_HOURS_LIMIT = new BigDecimal("30");
    /** 出勤率がこの割合（%）以上なら付与する */
    public static final BigDecimal REQUIRED_ATTENDANCE_RATE = new BigDecimal("80");
    /** 年 5 日取得義務の対象になる付与日数 */
    public static final BigDecimal OBLIGATION_THRESHOLD = BigDecimal.TEN;
    public static final BigDecimal OBLIGATION_DAYS = new BigDecimal("5");

    private LeaveGrantPolicy() {
    }

    /** 1 回分の付与 */
    public record Schedule(int index, LocalDate grantDate, BigDecimal days,
                           LocalDate periodStart, LocalDate periodEnd) {
        /** 有効期限（付与日の 2 年後の前日） */
        public LocalDate expiryDate() {
            return expiryOf(grantDate);
        }
    }

    public static LocalDate expiryOf(LocalDate grantDate) {
        return grantDate.plusYears(2).minusDays(1);
    }

    /** 比例付与の対象か：週 4 日以下、かつ週の所定労働時間が 30 時間未満（時間が未設定なら対象外） */
    public static boolean isProportional(User user) {
        Integer days = user.getWeeklyWorkDays();
        return days != null && days >= 1 && days <= 4
                && user.getWeeklyWorkHours() != null
                && user.getWeeklyWorkHours().compareTo(PROPORTIONAL_HOURS_LIMIT) < 0;
    }

    /** index 回目（0 始まり：0 = 入社 6 か月後）の付与日数 */
    public static BigDecimal daysFor(User user, int index) {
        int column = Math.min(index, FULL_TIME.length - 1);
        int days = isProportional(user) ? PROPORTIONAL[user.getWeeklyWorkDays() - 1][column] : FULL_TIME[column];
        return BigDecimal.valueOf(days).setScale(1);
    }

    /**
     * 入社日から指定日（含む）までの付与予定。出勤率の算定期間（前回の付与日〜今回の付与日の前日、
     * 初回は入社日〜6 か月後の前日）も返す。
     */
    public static List<Schedule> schedulesUntil(User user, LocalDate until) {
        List<Schedule> result = new ArrayList<>();
        LocalDate hireDate = user.getHireDate();
        if (hireDate == null) {
            return result;
        }
        LocalDate periodStart = hireDate;
        for (int index = 0; ; index++) {
            LocalDate grantDate = hireDate.plusMonths(6).plusYears(index);
            if (grantDate.isAfter(until)) {
                break;
            }
            result.add(new Schedule(index, grantDate, daysFor(user, index), periodStart, grantDate.minusDays(1)));
            periodStart = grantDate;
        }
        return result;
    }

    /** 次回（指定日より後）の付与予定 */
    public static Schedule nextSchedule(User user, LocalDate after) {
        if (user.getHireDate() == null) {
            return null;
        }
        List<Schedule> past = schedulesUntil(user, after);
        int index = past.size();
        LocalDate grantDate = user.getHireDate().plusMonths(6).plusYears(index);
        LocalDate periodStart = past.isEmpty() ? user.getHireDate() : past.get(past.size() - 1).grantDate();
        return new Schedule(index, grantDate, daysFor(user, index), periodStart, grantDate.minusDays(1));
    }
}
