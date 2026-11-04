package com.wkrepo.system.service;

import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.LocalDate;

@Service
public class HolidayService {

    /**
     * 指定日が休日かどうかを判定する（週末・祝日・振替休日を含む）
     */
    public boolean isRestDay(LocalDate date) {
        // 1. 週末の判定
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return true;
        }

        // 2. 祝日の判定
        if (isNationalHoliday(date)) {
            return true;
        }

        // 3. 振替休日の判定
        if (isSubstituteHoliday(date)) {
            return true;
        }

        // 4. 国民の休日の判定（祝日に挟まれた日）
        return isCitizensHoliday(date);
    }

    /**
     * 祝日の定義（すべて計算で求める）
     */
    public boolean isNationalHoliday(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        DayOfWeek dow = date.getDayOfWeek();

        switch (month) {
            case 1:
                if (day == 1) return true; // 元旦
                if (day == getNthMonday(year, month, 2)) return true; // 成人の日
                break;
            case 2:
                if (day == 11) return true; // 建国記念の日
                if (day == 23) return true; // 天皇誕生日
                break;
            case 3:
                if (day == calculateVernalEquinox(year)) return true; // 春分の日
                break;
            case 4:
                if (day == 29) return true; // 昭和の日
                break;
            case 5:
                if (day == 3 || day == 4 || day == 5) return true; // 憲法記念日、みどりの日、こどもの日
                break;
            case 7:
                if (day == getNthMonday(year, month, 3)) return true; // 海の日
                break;
            case 8:
                if (day == 11) return true; // 山の日
                break;
            case 9:
                if (day == getNthMonday(year, month, 3)) return true; // 敬老の日
                if (day == calculateAutumnalEquinox(year)) return true; // 秋分の日
                break;
            case 10:
                if (day == getNthMonday(year, month, 2)) return true; // 体育の日/スポーツの日
                break;
            case 11:
                if (day == 3) return true; // 文化の日
                if (day == 23) return true; // 勤労感謝の日
                break;
        }
        return false;
    }

    /**
     * 第 N 月曜日の日付を取得
     */
    private int getNthMonday(int year, int month, int n) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        int firstMonday = (DayOfWeek.MONDAY.getValue() - firstDay.getDayOfWeek().getValue() + 7) % 7 + 1;
        return firstMonday + (n - 1) * 7;
    }

    /**
     * 春分日の簡易計算式
     */
    private int calculateVernalEquinox(int year) {
        return (int) (20.8431 + 0.242194 * (year - 1980) - (int)((year - 1980) / 4.0));
    }

    /**
     * 秋分日の簡易計算式
     */
    private int calculateAutumnalEquinox(int year) {
        return (int) (23.2488 + 0.242194 * (year - 1980) - (int)((year - 1980) / 4.0));
    }

    /**
     * 国民の休日：祝日に挟まれた平日も休日になる（9 月に多い）
     */
    private boolean isCitizensHoliday(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY || date.getDayOfWeek() == DayOfWeek.SATURDAY) return false;
        return isNationalHoliday(date.minusDays(1)) && isNationalHoliday(date.plusDays(1));
    }

    /**
     * 振替休日の判定：
     * 前日が祝日であれば、さかのぼって探し続ける。
     * 連続する祝日の起点が「日曜日」であれば、当日（祝日ではない日）は振替休日となる。
     */
    private boolean isSubstituteHoliday(LocalDate date) {
        // 振替休日が日曜日になることはない
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }

        LocalDate checkDate = date.minusDays(1);
        
        // 前日が祝日でなければ振替休日ではない
        if (!isNationalHoliday(checkDate)) {
            return false;
        }

        // 連続する祝日をさかのぼり、起点となる日曜日があるかを探す
        while (isNationalHoliday(checkDate)) {
            if (checkDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                return true; // 見つかった（例：5月3日が日曜日のため5月6日が振替休日になる）
            }
            checkDate = checkDate.minusDays(1);
        }

        return false;
    }
}