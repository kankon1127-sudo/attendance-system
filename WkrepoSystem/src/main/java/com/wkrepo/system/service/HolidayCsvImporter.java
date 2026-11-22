package com.wkrepo.system.service;

import com.wkrepo.system.entity.Holiday;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.HolidayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 内閣府「国民の祝日」CSV を holidays テーブルに取り込む。同梱の holidays/syukujitsu.csv（UTF-8 に変換済み）か、
 * 祝日管理画面でアップロードされた CSV を読む。
 * テーブルにない日付だけを追加するので、管理者が削除した祝日や名前を変えた祝日は上書きしない。
 * 出典：https://www8.cao.go.jp/chosei/shukujitsu/gaiyou.html （毎年 2 月ごろ翌年分が追加される）
 */
@Component
public class HolidayCsvImporter {

    private static final Logger logger = LoggerFactory.getLogger(HolidayCsvImporter.class);
    static final String CSV_PATH = "holidays/syukujitsu.csv";
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ofPattern("yyyy/M/d");

    private final HolidayRepository holidayRepository;
    private final HolidayService holidayService;

    public HolidayCsvImporter(HolidayRepository holidayRepository, HolidayService holidayService) {
        this.holidayRepository = holidayRepository;
        this.holidayService = holidayService;
    }

    /** 同梱の CSV のうちテーブルにない日付を追加し、追加した件数を返す */
    @Transactional
    public int importMissing() {
        return importMissing(readBundledCsv());
    }

    /**
     * アップロードされた CSV（内閣府サイトからダウンロードしたままの Shift_JIS でも UTF-8 でもよい）のうち、
     * テーブルにない日付を追加する。祝日の行が 1 件もなければ形式違いとしてエラーにする。
     */
    @Transactional
    public int importMissing(byte[] csv) {
        List<Holiday> holidays;
        try {
            holidays = parse(new StringReader(decode(csv)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (holidays.isEmpty()) {
            throw new BusinessException("holiday.error.invalidCsv");
        }
        return importMissing(holidays);
    }

    private int importMissing(List<Holiday> holidays) {
        Set<LocalDate> existing = new HashSet<>();
        holidayRepository.findAll().forEach(holiday -> existing.add(holiday.getDate()));

        List<Holiday> toAdd = new ArrayList<>();
        for (Holiday holiday : holidays) {
            if (existing.add(holiday.getDate())) {
                toAdd.add(holiday);
            }
        }
        holidayRepository.saveAll(toAdd);
        holidayService.clearCache();
        return toAdd.size();
    }

    List<Holiday> readBundledCsv() {
        try (Reader reader = new InputStreamReader(new ClassPathResource(CSV_PATH).getInputStream(), StandardCharsets.UTF_8)) {
            return parse(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("祝日 CSV を読み込めませんでした: " + CSV_PATH, e);
        }
    }

    /** UTF-8（BOM 付きも可）として読めなければ Shift_JIS（MS932）として読む */
    static String decode(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, Charset.forName("MS932"));
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /** 「yyyy/M/d,名称」形式の CSV を読む。見出し行や形式が不正な行は読み飛ばす */
    static List<Holiday> parse(Reader source) throws IOException {
        List<Holiday> holidays = new ArrayList<>();
        BufferedReader reader = new BufferedReader(source);
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.strip().split(",", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                continue;
            }
            try {
                holidays.add(new Holiday(LocalDate.parse(parts[0].strip(), CSV_DATE), parts[1].strip()));
            } catch (DateTimeParseException e) {
                if (!holidays.isEmpty()) {
                    logger.warn("祝日 CSV の行を読み飛ばしました（日付の形式が不正）: {}", line);
                }
            }
        }
        return holidays;
    }
}
