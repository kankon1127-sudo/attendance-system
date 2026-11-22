package com.wkrepo.system.config;

import com.wkrepo.system.service.LeaveGrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 年休の自動付与と年 5 日取得義務のリマインド。
 * 起動時（祝日の初期化の後）に旧方式のデータを移行してから 1 回実行し、以降は毎日（既定 6:30）実行する。
 */
@Component
@Order(20)
public class LeaveGrantRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(LeaveGrantRunner.class);

    private final LeaveGrantService leaveGrantService;
    private final boolean enabled;

    public LeaveGrantRunner(LeaveGrantService leaveGrantService,
                            @Value("${app.leave.auto-grant-enabled:true}") boolean enabled) {
        this.leaveGrantService = leaveGrantService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            leaveGrantService.migrateLegacyEntitlements();
        } catch (RuntimeException e) {
            logger.error("旧方式の年休データの移行でエラー", e);
        }
        daily();
    }

    @Scheduled(cron = "${app.leave.grant-cron:0 30 6 * * *}")
    public void daily() {
        if (!enabled) {
            return;
        }
        try {
            leaveGrantService.autoGrantAll();
            leaveGrantService.remindObligations();
        } catch (RuntimeException e) {
            logger.error("年休の自動付与・年 5 日リマインドでエラー", e);
        }
    }
}
