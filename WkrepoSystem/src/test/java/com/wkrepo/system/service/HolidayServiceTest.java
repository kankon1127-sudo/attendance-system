package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.Holiday;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.HolidayRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HolidayServiceTest {

    /** 祝日テーブルにデータがない年は、従来の計算式で判定する */
    @Nested
    class AlgorithmFallback {

        private final HolidayService holidayService = TestData.holidayService();

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

    /** 祝日テーブル（内閣府 CSV を取り込んだ状態）で判定する */
    @Nested
    class RegisteredHolidays {

        private final List<Holiday> table = new ArrayList<>();
        private HolidayRepository repository;
        private HolidayService holidayService;

        @BeforeEach
        void setUp() throws Exception {
            table.addAll(new HolidayCsvImporter(null, null).readBundledCsv());
            repository = Mockito.mock(HolidayRepository.class);
            when(repository.findByDateBetweenOrderByDateAsc(any(), any())).thenAnswer(invocation -> {
                LocalDate from = invocation.getArgument(0);
                LocalDate to = invocation.getArgument(1);
                return table.stream().filter(h -> !h.getDate().isBefore(from) && !h.getDate().isAfter(to)).toList();
            });
            holidayService = new HolidayService(repository, CompanyHolidayCalendar.empty());
        }

        @ParameterizedTest(name = "{0} {1} → {2}")
        @CsvSource({
                // 計算式では判定できない臨時の祝日・移動した祝日
                "2019-05-01, 天皇の即位の日,                         true",
                "2019-10-22, 即位礼正殿の儀,                         true",
                "2020-07-24, スポーツの日（東京五輪で移動）,         true",
                "2020-10-12, 2020年は祝日ではない（10月第2月曜）,     false",
                "2021-08-09, 山の日の振替休日（東京五輪で移動）,     true",
                "2021-08-11, 2021年は祝日ではない（山の日が移動）,   false",
                "2019-12-23, 平成の天皇誕生日（2019年からは平日）,   false",
                "2018-12-24, 平成の天皇誕生日の振替休日,             true",
                "2026-11-03, 文化の日,                               true",
                "2026-11-02, 平日,                                   false",
        })
        void usesRegisteredHolidays(LocalDate date, String description, boolean expected) {
            assertThat(holidayService.isRestDay(date)).isEqualTo(expected);
        }

        @Test
        void returnsHolidayName() {
            assertThat(holidayService.getHolidayName(LocalDate.of(2026, 11, 3))).contains("文化の日");
            assertThat(holidayService.getHolidayName(LocalDate.of(2026, 11, 2))).isEmpty();
        }

        @Test
        void deletedHolidayIsNotRevivedByAlgorithm() {
            // 管理者が 2026/11/3 を削除した場合、計算式に戻らず平日として扱う
            table.removeIf(h -> h.getDate().equals(LocalDate.of(2026, 11, 3)));

            assertThat(holidayService.isRestDay(LocalDate.of(2026, 11, 3))).isFalse();
        }

        @Test
        void yearWithoutDataFallsBackToAlgorithm() {
            // CSV は 2027 年まで。2030 年は計算式で判定する
            assertThat(holidayService.isRestDay(LocalDate.of(2030, 11, 4))).isTrue(); // 文化の日(11/3 日曜)の振替休日
            assertThat(holidayService.getHolidayName(LocalDate.of(2030, 11, 4))).isEmpty();
        }

        @Test
        void cachesPerYearAndClearsAfterAdd() {
            holidayService.isRestDay(LocalDate.of(2026, 6, 1));
            holidayService.isRestDay(LocalDate.of(2026, 6, 2));
            verify(repository, times(1)).findByDateBetweenOrderByDateAsc(any(), any());

            when(repository.save(any(Holiday.class))).thenAnswer(invocation -> {
                Holiday holiday = invocation.getArgument(0);
                table.add(holiday);
                return holiday;
            });
            holidayService.addHoliday(LocalDate.of(2026, 6, 1), "創立記念日");

            assertThat(holidayService.isRestDay(LocalDate.of(2026, 6, 1))).isTrue();
            assertThat(holidayService.getHolidayName(LocalDate.of(2026, 6, 1))).contains("創立記念日");
        }

        @Test
        void rejectsDuplicateAndBlankName() {
            when(repository.existsByDate(LocalDate.of(2026, 11, 3))).thenReturn(true);

            assertThatThrownBy(() -> holidayService.addHoliday(LocalDate.of(2026, 11, 3), "文化の日"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> holidayService.addHoliday(LocalDate.of(2026, 6, 1), " "))
                    .hasMessage("holiday.error.nameRequired");
            verify(repository, never()).save(any());
        }
    }

    /** 会社休日（設定ファイル） */
    @Nested
    class CompanyHolidays {

        private final CompanyHolidayCalendar calendar = CompanyHolidayCalendar.fromText("""
                # コメント行
                12-29,年末休暇
                01-03,年始休暇
                2026-08-14,夏季休暇
                13-40,存在しない日付
                abc,形式が不正
                """);

        private final HolidayService holidayService = new HolidayService(emptyRepository(), calendar);

        @Test
        void everyYearAndSpecificDatesAreRestDays() {
            assertThat(holidayService.isRestDay(LocalDate.of(2026, 12, 29))).isTrue();
            assertThat(holidayService.isRestDay(LocalDate.of(2027, 12, 29))).isTrue();
            assertThat(holidayService.isRestDay(LocalDate.of(2026, 8, 14))).isTrue();
            assertThat(holidayService.isRestDay(LocalDate.of(2027, 8, 13))).isFalse(); // 2027/8/14 は土曜、8/13 は平日
            assertThat(holidayService.getHolidayName(LocalDate.of(2026, 12, 29))).contains("年末休暇");
        }

        @Test
        void listsCompanyHolidaysOfYearInDateOrder() {
            assertThat(calendar.listFor(2026).keySet()).containsExactly(
                    LocalDate.of(2026, 1, 3), LocalDate.of(2026, 8, 14), LocalDate.of(2026, 12, 29));
        }

        private HolidayRepository emptyRepository() {
            HolidayRepository repository = Mockito.mock(HolidayRepository.class);
            when(repository.findByDateBetweenOrderByDateAsc(any(), any())).thenReturn(List.of());
            return repository;
        }
    }
}
