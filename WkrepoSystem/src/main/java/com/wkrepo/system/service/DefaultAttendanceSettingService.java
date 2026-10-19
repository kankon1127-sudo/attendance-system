package com.wkrepo.system.service;

import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.DefaultAttendanceSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DefaultAttendanceSettingService {

    @Autowired
    private DefaultAttendanceSettingRepository defaultAttendanceSettingRepository;

    public DefaultAttendanceSetting getOrCreateDefaultSetting(User user) {
        return defaultAttendanceSettingRepository.findByUser(user)
                .orElse(new DefaultAttendanceSetting(user));
    }

    public DefaultAttendanceSetting save(DefaultAttendanceSetting setting) {
        return defaultAttendanceSettingRepository.save(setting);
    }

    public DefaultAttendanceSetting updateOrCreateDefaultSetting(User user, 
            java.time.LocalTime defaultStartTime, 
            java.time.LocalTime defaultEndTime, 
            Integer defaultBreakMinutes, 
            String defaultAgreement36Status, 
            String defaultApprover) {
        
        DefaultAttendanceSetting setting = defaultAttendanceSettingRepository.findByUser(user)
                .orElse(new DefaultAttendanceSetting(user));

        setting.setDefaultStartTime(defaultStartTime);
        setting.setDefaultEndTime(defaultEndTime);
        setting.setDefaultBreakMinutes(defaultBreakMinutes);
        setting.setDefaultAgreement36Status(defaultAgreement36Status);
        setting.setDefaultApprover(defaultApprover);

        return defaultAttendanceSettingRepository.save(setting);
    }
}