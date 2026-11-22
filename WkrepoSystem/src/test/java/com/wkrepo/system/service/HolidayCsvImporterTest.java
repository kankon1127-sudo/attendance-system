package com.wkrepo.system.service;

import com.wkrepo.system.entity.Holiday;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.HolidayRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void decodesShiftJisAndUtf8WithBom() {
        String csv = "国民の祝日・休日月日,国民の祝日・休日名称\r\n2028/1/1,元日\r\n";

        assertThat(HolidayCsvImporter.decode(csv.getBytes(Charset.forName("MS932")))).isEqualTo(csv);
        assertThat(HolidayCsvImporter.decode(("﻿" + csv).getBytes(StandardCharsets.UTF_8))).isEqualTo(csv);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsUploadedShiftJisCsv() {
        HolidayRepository repository = Mockito.mock(HolidayRepository.class);
        HolidayService holidayService = Mockito.mock(HolidayService.class);
        when(repository.findAll()).thenReturn(List.of(new Holiday(LocalDate.of(2028, 1, 1), "元日")));
        byte[] csv = "国民の祝日・休日月日,国民の祝日・休日名称\r\n2028/1/1,元日\r\n2028/1/10,成人の日\r\n"
                .getBytes(Charset.forName("MS932"));

        int imported = new HolidayCsvImporter(repository, holidayService).importMissing(csv);

        assertThat(imported).isEqualTo(1);
        ArgumentCaptor<List<Holiday>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue()).singleElement().satisfies(h -> {
            assertThat(h.getDate()).isEqualTo(LocalDate.of(2028, 1, 10));
            assertThat(h.getName()).isEqualTo("成人の日");
        });
    }

    @Test
    void rejectsCsvWithoutHolidays() {
        HolidayCsvImporter importer = new HolidayCsvImporter(Mockito.mock(HolidayRepository.class),
                Mockito.mock(HolidayService.class));

        assertThatThrownBy(() -> importer.importMissing("a,b\r\nc,d\r\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("holiday.error.invalidCsv");
    }
}
