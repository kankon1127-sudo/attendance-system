package com.wkrepo.system.service;

import com.wkrepo.system.entity.Holiday;
import com.wkrepo.system.repository.HolidayRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HolidayCsvImporterTest {

    @Test
    void bundledCsvIsReadable() {
        List<Holiday> holidays = new HolidayCsvImporter(null, null).readBundledCsv();

        assertThat(holidays).hasSizeGreaterThan(1000);
        assertThat(holidays.get(0).getDate()).isEqualTo(LocalDate.of(1955, 1, 1));
        assertThat(holidays).anySatisfy(h -> {
            assertThat(h.getDate()).isEqualTo(LocalDate.of(2026, 11, 3));
            assertThat(h.getName()).isEqualTo("文化の日");
        });
    }

    @Test
    void parseSkipsHeaderAndBrokenLines() throws Exception {
        List<Holiday> holidays = HolidayCsvImporter.parse(new StringReader(
                "国民の祝日・休日月日,国民の祝日・休日名称\r\n2026/1/1,元日\r\n\r\n2026/13/1,不正\r\n2026/1/12,成人の日\r\n"));

        assertThat(holidays).extracting(Holiday::getName).containsExactly("元日", "成人の日");
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsOnlyMissingDates() {
        HolidayRepository repository = Mockito.mock(HolidayRepository.class);
        HolidayService holidayService = Mockito.mock(HolidayService.class);
        // 既に登録済み：2026/11/3（名前は管理者が変更済み）
        when(repository.findAll()).thenReturn(List.of(new Holiday(LocalDate.of(2026, 11, 3), "文化の日（変更済み）")));

        int imported = new HolidayCsvImporter(repository, holidayService).importMissing();

        ArgumentCaptor<List<Holiday>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(imported);
        assertThat(saved.getValue()).noneMatch(h -> h.getDate().equals(LocalDate.of(2026, 11, 3)));
        verify(holidayService).clearCache();
    }
}
