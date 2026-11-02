package com.wkrepo.system.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HolidayServiceTest {

    private final HolidayService holidayService = new HolidayService();

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource({
            "2026-11-02, 平日,                     false",
            "2026-11-01, 日曜日,                   true",
            "2026-11-07, 土曜日,                   true",
            "2026-11-03, 文化の日,                 true",
            "2026-11-23, 勤労感謝の日,             true",
            "2026-01-12, 成人の日（1月第2月曜）,   true",
            "2026-05-06, 振替休日（5/3 が日曜）,   true",
            "2026-09-21, 敬老の日,                 true",
            "2026-09-22, 国民の休日,               true",
            "2026-09-23, 秋分の日,                 true",
            "2026-03-20, 春分の日,                 true",
            "2026-12-28, 平日,                     false",
    })
    void detectsRestDays(LocalDate date, String description, boolean expected) {
        assertThat(holidayService.isRestDay(date)).isEqualTo(expected);
    }
}
