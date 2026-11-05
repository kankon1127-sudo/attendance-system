package com.wkrepo.system.config;

import com.wkrepo.system.service.AttendanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 起動時に現行ルールで未ロックの月の実働時間と残業時間を再計算する。
 * 旧バージョンの「休憩時間を差し引かない」「フロントエンドから送られた値をそのまま保存する」処理で残ったデータを修正する。結果が変わらないレコードは更新しない。
 */
@Component
@Order(10) // 祝日の取り込み（HolidayInitializer）の後に実行する
public class AttendanceRecalculationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AttendanceRecalculationRunner.class);

    @Autowired
    private AttendanceService attendanceService;

    @Override
    public void run(ApplicationArguments args) {
        int changed = attendanceService.recalculateUnlockedAttendances();
        if (changed > 0) {
            logger.info("現行ルールで {} 件の勤怠レコードの実働時間と残業時間を再計算しました（月報提出済みの月を除く）", changed);
        }
    }
}
