package com.wkrepo.system.service;

import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.LocalDate;

@Service
public class HolidayService {

    /**
     * 判断指定日期是否为休息日（含周末、法定祝日、振替休日）
     */
    public boolean isRestDay(LocalDate date) {
        // 1. 周末判断
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return true;
        }

        // 2. 法定祝日判断
        if (isNationalHoliday(date)) {
            return true;
        }

        // 3. 振替休日（补休）逻辑
        if (isSubstituteHoliday(date)) {
            return true;
        }

        // 4. 国民之休日逻辑（夹在两个祝日中间的一天）
        return isCitizensHoliday(date);
    }

    /**
     * 定义法定祝日（全算法化实现）
     */
    public boolean isNationalHoliday(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        DayOfWeek dow = date.getDayOfWeek();

        switch (month) {
            case 1:
                if (day == 1) return true; // 元旦
                if (day == getNthMonday(year, month, 2)) return true; // 成人之日
                break;
            case 2:
                if (day == 11) return true; // 建国纪念之日
                if (day == 23) return true; // 天皇诞生日
                break;
            case 3:
                if (day == calculateVernalEquinox(year)) return true; // 春分之日
                break;
            case 4:
                if (day == 29) return true; // 昭和之日
                break;
            case 5:
                if (day == 3 || day == 4 || day == 5) return true; // 宪法纪念日、绿之日、儿童之日
                break;
            case 7:
                if (day == getNthMonday(year, month, 3)) return true; // 海之日
                break;
            case 8:
                if (day == 11) return true; // 山之日
                break;
            case 9:
                if (day == getNthMonday(year, month, 3)) return true; // 敬老之日
                if (day == calculateAutumnalEquinox(year)) return true; // 秋分之日
                break;
            case 10:
                if (day == getNthMonday(year, month, 2)) return true; // 体育之日/运动之日
                break;
            case 11:
                if (day == 3) return true; // 文化之日
                if (day == 23) return true; // 勤劳感谢之日
                break;
        }
        return false;
    }

    /**
     * 获取第 N 个星期一的日期
     */
    private int getNthMonday(int year, int month, int n) {
        LocalDate firstDay = LocalDate.of(year, month, 1);
        int firstMonday = (DayOfWeek.MONDAY.getValue() - firstDay.getDayOfWeek().getValue() + 7) % 7 + 1;
        return firstMonday + (n - 1) * 7;
    }

    /**
     * 简易春分计算公式
     */
    private int calculateVernalEquinox(int year) {
        return (int) (20.8431 + 0.242194 * (year - 1980) - (int)((year - 1980) / 4.0));
    }

    /**
     * 简易秋分计算公式
     */
    private int calculateAutumnalEquinox(int year) {
        return (int) (23.2488 + 0.242194 * (year - 1980) - (int)((year - 1980) / 4.0));
    }

    /**
     * 国民之休日：夹在两个祝日中间的一个工作日也要放假（常见于 9 月）
     */
    private boolean isCitizensHoliday(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY || date.getDayOfWeek() == DayOfWeek.SATURDAY) return false;
        return isNationalHoliday(date.minusDays(1)) && isNationalHoliday(date.plusDays(1));
    }

    /**
     * 振替休日算法优化：
     * 向上溯源：如果前一天是祝日，则一直往回找。
     * 如果这一串连续祝日的起点是“星期日”，那么当前这个“非祝日”就是合法的振替休日。
     */
    private boolean isSubstituteHoliday(LocalDate date) {
        // 振替休日本身不可能是周日
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }

        LocalDate checkDate = date.minusDays(1);
        
        // 如果前一天不是祝日，当前肯定不是振替休日
        if (!isNationalHoliday(checkDate)) {
            return false;
        }

        // 关键逻辑：沿着祝日链条向后退，寻找是否有周日作为“锚点”
        while (isNationalHoliday(checkDate)) {
            if (checkDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                return true; // 找到了，5月6日的补休是因为5月3日是周日
            }
            checkDate = checkDate.minusDays(1);
        }

        return false;
    }
}