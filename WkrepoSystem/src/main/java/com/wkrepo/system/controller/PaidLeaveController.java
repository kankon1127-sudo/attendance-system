package com.wkrepo.system.controller;

import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.ApplicationNotificationService;
import com.wkrepo.system.service.PaidLeaveService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Controller
@RequestMapping("/paid-leave")
public class PaidLeaveController {
    
    private static final Logger logger = LoggerFactory.getLogger(PaidLeaveController.class);
    
    @Autowired
    private PaidLeaveService paidLeaveService;

    @Autowired
    private ApplicationNotificationService applicationNotificationService;
    
    @GetMapping
    public String showPaidLeave(HttpSession session, Model model) {
        
        logger.info("Entering showPaidLeave");
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            logger.warn("User not authenticated, redirecting to login");
            return "redirect:/login";
        }
        
        logger.debug("Processing for user: {}", user.getId());
        
        List<PaidLeave> leaves = paidLeaveService.getPaidLeavesByUser(user);
        
        logger.debug("Retrieved {} leaves for user", leaves.size());
        
        // 计算统计数据（模拟数据，实际应从用户配置或其他表获取）
        BigDecimal remainingDays = new BigDecimal("40.0");  // 年假残日数
        BigDecimal usedDays = leaves.stream()
                .map(PaidLeave::getLeaveDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);  // 取得済み日数
        BigDecimal daysTo5Year = new BigDecimal("5.0");  // 5日義務まで
        BigDecimal grantedDays = new BigDecimal("20.0");  // 付与日数
        
        model.addAttribute("leaves", leaves);
        model.addAttribute("remainingDays", remainingDays);
        model.addAttribute("usedDays", usedDays);
        model.addAttribute("daysTo5Year", daysTo5Year);
        model.addAttribute("grantedDays", grantedDays);
        model.addAttribute("user", user);
        
        model.addAttribute("newLeave", new PaidLeave());
        
        logger.info("Successfully prepared model attributes for paid leave view");
        
        return "paid-leave";
    }
    
    @PostMapping("/save")
    public String savePaidLeave(PaidLeave paidLeave, HttpSession session, RedirectAttributes redirectAttributes) {
        
        logger.info("Entering savePaidLeave - type: {}, startDate: {}, endDate: {}, days: {}", 
                   paidLeave.getLeaveType(), paidLeave.getStartDate(), paidLeave.getEndDate(), paidLeave.getLeaveDays());
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            logger.warn("User not authenticated, redirecting to login");
            return "redirect:/login";
        }
        
        try {
            // 如果没有设置天数，自动计算
            if (paidLeave.getLeaveDays() == null || paidLeave.getLeaveDays().compareTo(BigDecimal.ZERO) == 0) {
                long daysBetween = ChronoUnit.DAYS.between(paidLeave.getStartDate(), paidLeave.getEndDate()) + 1;
                paidLeave.setLeaveDays(new BigDecimal(daysBetween));
            }
            
            paidLeave.setUser(user);
            PaidLeave savedLeave = paidLeaveService.savePaidLeave(paidLeave);

            ApplicationNotificationService.Result notificationResult = applicationNotificationService.notifyManager(
                    user,
                    "【Wkrepo】年休申請の承認依頼",
                    "取得期間: " + savedLeave.getStartDate() + " ～ " + savedLeave.getEndDate()
                            + "\n区分: " + savedLeave.getLeaveType()
                            + "\n日数: " + savedLeave.getLeaveDays());
            if (notificationResult != ApplicationNotificationService.Result.NOT_APPLICABLE) {
                redirectAttributes.addFlashAttribute("notificationStatus", notificationResult.name());
            }
            
            logger.debug("Saved leave with ID: {}, startDate: {}, endDate: {}", 
                        savedLeave.getId(), savedLeave.getStartDate(), savedLeave.getEndDate());
            
            logger.info("Successfully saved paid leave");
            
            return "redirect:/paid-leave";
        } catch (Exception e) {
            logger.error("Error saving paid leave", e);
            return "redirect:/paid-leave";
        }
    }
    
    @PostMapping("/delete/{id}")
    public String deletePaidLeave(@PathVariable Long id) {
        logger.info("Deleting paid leave with ID: {}", id);
        
        try {
            paidLeaveService.deletePaidLeave(id);
            logger.info("Successfully deleted paid leave with ID: {}", id);
        } catch (Exception e) {
            logger.error("Error deleting paid leave with ID: {}", id, e);
        }
        
        return "redirect:/paid-leave";
    }
}
