package com.wkrepo.system.config;

import com.wkrepo.system.service.AttendanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时按当前规则重算未锁定月份的工时和加班。
 * 修正旧版本中「不扣休息时间」「直接保存前端传来的值」留下的数据；结果相同的记录不会被更新。
 */
@Component
public class AttendanceRecalculationRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AttendanceRecalculationRunner.class);

    @Autowired
    private AttendanceService attendanceService;

    @Override
    public void run(ApplicationArguments args) {
        int changed = attendanceService.recalculateUnlockedAttendances();
        if (changed > 0) {
            logger.info("按当前规则重算了 {} 条勤怠记录的工时和加班（已提交月报的月份除外）", changed);
        }
    }
}
