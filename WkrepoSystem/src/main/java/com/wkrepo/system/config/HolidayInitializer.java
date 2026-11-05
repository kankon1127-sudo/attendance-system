package com.wkrepo.system.config;

import com.wkrepo.system.repository.HolidayRepository;
import com.wkrepo.system.service.HolidayCsvImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 起動時、祝日テーブルが空なら内閣府の CSV から取り込む（初回起動・デモ環境の再デプロイ時）。
 * 勤怠の再計算（{@link AttendanceRecalculationRunner}）が休日判定を使うため、それより先に実行する。
 */
@Component
@Order(1)
public class HolidayInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(HolidayInitializer.class);

    private final HolidayRepository holidayRepository;
    private final HolidayCsvImporter holidayCsvImporter;

    public HolidayInitializer(HolidayRepository holidayRepository, HolidayCsvImporter holidayCsvImporter) {
        this.holidayRepository = holidayRepository;
        this.holidayCsvImporter = holidayCsvImporter;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (holidayRepository.count() == 0) {
            int imported = holidayCsvImporter.importMissing();
            logger.info("祝日テーブルが空だったため、CSV から {} 件の祝日を取り込みました", imported);
        }
    }
}
