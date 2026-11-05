package com.wkrepo.system.service;

import com.wkrepo.system.entity.Holiday;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.HolidayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 休日の判定。休日 = 土日 + 国民の祝日・休日 + 会社休日。
 * <ul>
 *   <li>国民の祝日・休日は holidays テーブル（祝日管理画面で管理）から判定する。</li>
 *   <li>テーブルにその年のデータが 1 件もない場合だけ、従来の計算式（振替休日・国民の休日を含む）で判定する。</li>
 *   <li>会社休日は設定ファイル（{@link CompanyHolidayCalendar}）から判定する。</li>
 * </ul>
 * 年ごとの祝日はメモリにキャッシュし、追加・削除・取り込みのたびにクリアする。
 */
@Service
public class HolidayService {

    private final HolidayRepository holidayRepository;
    private final CompanyHolidayCalendar companyHolidays;
    private final Map<Integer, Map<LocalDate, String>> registeredByYear = new ConcurrentHashMap<>();

    public HolidayService(HolidayRepository holidayRepository, CompanyHolidayCalendar companyHolidays) {
        this.holidayRepository = holidayRepository;
        this.companyHolidays = companyHolidays;
    }

    /**
     * 指定日が休日かどうか（土日・国民の祝日・休日・会社休日）
     */
    public boolean isRestDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return true;
        }
        return isNationalHoliday(date) || companyHolidays.nameOf(date).isPresent();
    }

    /**
     * 国民の祝日・休日（振替休日、国民の休日を含む）かどうか
     */
    public boolean isNationalHoliday(LocalDate date) {
        Map<LocalDate, String> registered = registeredHolidays(date.getYear());
        if (!registered.isEmpty()) {
            return registered.containsKey(date);
        }
        return isCalculatedHoliday(date) || isSubstituteHoliday(date) || isCitizensHoliday(date);
    }

    /**
     * 祝日名・会社休日名。土日、名前のない日（計算で判定した年の祝日）は空。
     */
    public Optional<String> getHolidayName(LocalDate date) {
        String name = registeredHolidays(date.getYear()).get(date);
        if (name != null) {
            return Optional.of(name);
        }
        return companyHolidays.nameOf(date);
    }

    /** 祝日管理画面用：指定した年の登録済みの祝日 */
    public List<Holiday> getRegisteredHolidays(int year) {
        return holidayRepository.findByDateBetweenOrderByDateAsc(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /** 祝日管理画面用：指定した年の会社休日 */
    public Map<LocalDate, String> getCompanyHolidays(int year) {
        return companyHolidays.listFor(year);
    }

    @Transactional
    public Holiday addHoliday(LocalDate date, String name) {
        if (date == null) {
            throw new IllegalArgumentException("holiday.error.dateRequired");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("holiday.error.nameRequired");
        }
        if (holidayRepository.existsByDate(date)) {
            throw new BusinessException("holiday.error.duplicate", date.toString());
        }
        Holiday saved = holidayRepository.save(new Holiday(date, name.strip()));
        clearCache();
        return saved;
    }

    @Transactional
    public void deleteHoliday(Long id) {
        holidayRepository.deleteById(id);
        clearCache();
    }

    public void clearCache() {
        registeredByYear.clear();
    }

    private Map<LocalDate, String> registeredHolidays(int year) {
        return registeredByYear.computeIfAbsent(year, y -> {
            Map<LocalDate, String> map = new LinkedHashMap<>();
            for (Holiday holiday : getRegisteredHolidays(y)) {
                map.put(holiday.getDate(), holiday.getName());
            }
            return map.isEmpty() ? Collections.emptyMap() : map;
        });
    }

    // ===== 以下、テーブルにデータがない年のための計算式（近似） =====

    /**
     * 祝日の定義（計算で求める）
     */
    private boolean isCalculatedHoliday(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();

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
        return isCalculatedHoliday(date.minusDays(1)) && isCalculatedHoliday(date.plusDays(1));
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
        if (!isCalculatedHoliday(checkDate)) {
            return false;
        }

        // 連続する祝日をさかのぼり、起点となる日曜日があるかを探す
        while (isCalculatedHoliday(checkDate)) {
            if (checkDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                return true; // 見つかった（例：5月3日が日曜日のため5月6日が振替休日になる）
            }
            checkDate = checkDate.minusDays(1);
        }

        return false;
    }
}
