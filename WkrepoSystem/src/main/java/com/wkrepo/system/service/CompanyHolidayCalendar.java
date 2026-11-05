package com.wkrepo.system.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 会社休日（年末年始など、国民の祝日以外の休み）。設定ファイル company-holidays.csv から起動時に読み込む。
 * 「MM-dd,名称」は毎年、「yyyy-MM-dd,名称」はその年だけの休日。
 */
@Component
public class CompanyHolidayCalendar {

    private static final Logger logger = LoggerFactory.getLogger(CompanyHolidayCalendar.class);

    private final Map<MonthDay, String> everyYear = new HashMap<>();
    private final Map<LocalDate, String> specificDates = new HashMap<>();

    // テスト用の引数なしコンストラクタもあるため、Spring が使うのはこちらだと明示する
    @Autowired
    public CompanyHolidayCalendar(ResourceLoader resourceLoader,
                                  @Value("${app.holidays.company-file:classpath:holidays/company-holidays.csv}") String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            logger.warn("会社休日のファイルが見つかりません: {}（会社休日なしで動作します）", location);
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            parse(reader);
        } catch (IOException e) {
            logger.error("会社休日のファイルを読み込めませんでした: {}", location, e);
        }
        logger.info("会社休日を読み込みました（毎年 {} 日、指定日 {} 日）", everyYear.size(), specificDates.size());
    }

    /** テスト用：会社休日なし */
    public static CompanyHolidayCalendar empty() {
        return new CompanyHolidayCalendar();
    }

    /** テスト用：CSV の内容から作る */
    public static CompanyHolidayCalendar fromText(String csv) {
        CompanyHolidayCalendar calendar = new CompanyHolidayCalendar();
        try {
            calendar.parse(new BufferedReader(new java.io.StringReader(csv)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return calendar;
    }

    private CompanyHolidayCalendar() {
    }

    void parse(BufferedReader reader) throws IOException {
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split(",", 2);
            String date = parts[0].strip();
            String name = parts.length > 1 && !parts[1].isBlank() ? parts[1].strip() : "会社休日";
            try {
                if (date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    specificDates.put(LocalDate.parse(date), name);
                } else if (date.matches("\\d{2}-\\d{2}")) {
                    everyYear.put(MonthDay.parse("--" + date), name);
                } else {
                    logger.warn("会社休日の {} 行目を読み飛ばしました（日付の形式が不正）: {}", lineNumber, line);
                }
            } catch (DateTimeException e) {
                logger.warn("会社休日の {} 行目を読み飛ばしました（存在しない日付）: {}", lineNumber, line);
            }
        }
    }

    public Optional<String> nameOf(LocalDate date) {
        String specific = specificDates.get(date);
        if (specific != null) {
            return Optional.of(specific);
        }
        return Optional.ofNullable(everyYear.get(MonthDay.from(date)));
    }

    /** 指定した年の会社休日（日付順） */
    public Map<LocalDate, String> listFor(int year) {
        Map<LocalDate, String> result = new TreeMap<>();
        everyYear.forEach((monthDay, name) -> {
            if (monthDay.isValidYear(year)) {
                result.put(monthDay.atYear(year), name);
            }
        });
        specificDates.forEach((date, name) -> {
            if (date.getYear() == year) {
                result.put(date, name);
            }
        });
        return result;
    }
}
