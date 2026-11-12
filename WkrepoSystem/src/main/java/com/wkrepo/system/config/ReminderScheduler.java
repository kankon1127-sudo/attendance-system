package com.wkrepo.system.config;

import com.wkrepo.system.service.ReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定期リマインドの実行（既定：平日の朝 8 時）。
 * app.reminder.enabled=false で止められる。アプリは 1 台で動かす前提（複数台なら ShedLock などで排他が必要）。
 */
@Configuration
@EnableScheduling
public class ReminderScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminderService;
    private final boolean enabled;

    public ReminderScheduler(ReminderService reminderService,
                             @Value("${app.reminder.enabled:true}") boolean enabled) {
        this.reminderService = reminderService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.reminder.cron:0 0 8 * * MON-FRI}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            reminderService.runAll();
        } catch (RuntimeException e) {
            logger.error("定期リマインドでエラー", e);
        }
    }
}
