package com.wkrepo.system.controller;

import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/default-attendance-setting")
public class DefaultAttendanceSettingController {

    @Autowired
    private DefaultAttendanceSettingService defaultAttendanceSettingService;

    @GetMapping
    public String showDefaultAttendanceSetting(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        DefaultAttendanceSetting setting = defaultAttendanceSettingService.getOrCreateDefaultSetting(user);
        model.addAttribute("setting", setting);
        model.addAttribute("user", user);

        return "default-attendance-setting";
    }

    @PostMapping("/save")
    public String saveDefaultAttendanceSetting(
            @RequestParam(value = "defaultStartTime", required = false) String defaultStartTimeStr,
            @RequestParam(value = "defaultEndTime", required = false) String defaultEndTimeStr,
            @RequestParam(value = "defaultBreakMinutes", required = false) Integer defaultBreakMinutes,
            @RequestParam(value = "defaultAgreement36Status", required = false) String defaultAgreement36Status,
            @RequestParam(value = "defaultApprover", required = false) String defaultApprover,
            HttpSession session) {
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        java.time.LocalTime defaultStartTime = null;
        java.time.LocalTime defaultEndTime = null;
        
        if (defaultStartTimeStr != null && !defaultStartTimeStr.isEmpty()) {
            defaultStartTime = java.time.LocalTime.parse(defaultStartTimeStr);
        }
        
        if (defaultEndTimeStr != null && !defaultEndTimeStr.isEmpty()) {
            defaultEndTime = java.time.LocalTime.parse(defaultEndTimeStr);
        }

        defaultAttendanceSettingService.updateOrCreateDefaultSetting(
                user, 
                defaultStartTime, 
                defaultEndTime, 
                defaultBreakMinutes, 
                defaultAgreement36Status, 
                defaultApprover
        );

        return "redirect:/default-attendance-setting?success";
    }
}