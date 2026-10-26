package com.wkrepo.system.controller;

import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.ApplicationNotificationService;
import com.wkrepo.system.service.PaidLeaveService;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
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

    @Autowired
    private MessageSource messageSource;
    
    @GetMapping
    public String showPaidLeave(
            @RequestParam(name = "year", required = false) Integer requestedYear,
            HttpSession session,
            Model model) {
        
        logger.info("Entering showPaidLeave");
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            logger.warn("User not authenticated, redirecting to login");
            return "redirect:/login";
        }
        
        logger.debug("Processing for user: {}", user.getId());
        int year = requestedYear == null ? LocalDate.now().getYear() : requestedYear;
        List<PaidLeave> leaves = paidLeaveService.getPaidLeavesByUser(user);
        logger.debug("Retrieved {} leaves for user", leaves.size());

        PaidLeaveService.Balance balance = paidLeaveService.getBalance(user, year);

        model.addAttribute("leaves", leaves);
        model.addAttribute("year", year);
        model.addAttribute("grantedDays", balance.grantedDays());
        model.addAttribute("usedDays", balance.usedDays());
        model.addAttribute("remainingDays", balance.remainingDays());
        model.addAttribute("pendingDays", balance.pendingDays());
        model.addAttribute("user", user);
        boolean canManageEntitlements = "ADMIN".equals(user.getRole());
        model.addAttribute("canManageEntitlements", canManageEntitlements);
        if (canManageEntitlements) {
            model.addAttribute("entitlements", paidLeaveService.getEntitlementsForYear(year));
        }
        
        model.addAttribute("newLeave", new PaidLeave());
        
        logger.info("Successfully prepared model attributes for paid leave view");
        
        return "paid-leave";
    }

    @PostMapping("/entitlement")
    public String saveEntitlement(
            @RequestParam("userId") Long userId,
            @RequestParam("year") int year,
            @RequestParam("grantedDays") BigDecimal grantedDays,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        if (!"ADMIN".equals(user.getRole())) {
            return "redirect:/home?error=permission";
        }
        try {
            paidLeaveService.setAnnualEntitlement(userId, year, grantedDays);
            redirectAttributes.addFlashAttribute("entitlementSaved", true);
        } catch (IllegalArgumentException exception) {
            logger.warn("Invalid annual leave entitlement userId={}, year={}", userId, year, exception);
            redirectAttributes.addFlashAttribute("entitlementError",
                    messageSource.getMessage(exception.getMessage(), null, exception.getMessage(),
                            LocaleContextHolder.getLocale()));
        }
        return "redirect:/paid-leave?year=" + year;
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
        
        LocalDate startDate = paidLeave.getStartDate();
        LocalDate endDate = paidLeave.getEndDate() == null ? startDate : paidLeave.getEndDate();
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            redirectAttributes.addFlashAttribute("leaveError", "leave.application.invalidDate");
            return "redirect:/paid-leave";
        }
        boolean halfDay = "午前".equals(paidLeave.getLeaveType()) || "午後".equals(paidLeave.getLeaveType());
        if (halfDay && !startDate.equals(endDate)) {
            redirectAttributes.addFlashAttribute("leaveError", "leave.application.halfDaySingleDate");
            return "redirect:/paid-leave";
        }

        paidLeave.setEndDate(endDate);
        if (halfDay) {
            paidLeave.setLeaveDays(new BigDecimal("0.5"));
        } else {
            long daysBetween = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            paidLeave.setLeaveDays(BigDecimal.valueOf(daysBetween));
        }

        paidLeave.setUser(user);
        PaidLeave savedLeave;
        try {
            savedLeave = paidLeaveService.savePaidLeave(paidLeave);
        } catch (RuntimeException exception) {
            logger.error("Error saving paid leave", exception);
            redirectAttributes.addFlashAttribute("leaveError", "leave.application.saveFailed");
            return "redirect:/paid-leave";
        }

        ApplicationNotificationService.Result notificationResult;
        try {
            notificationResult = applicationNotificationService.notifyManager(
                    user,
                    "【Wkrepo】年休申請の承認依頼",
                    "取得期間: " + savedLeave.getStartDate() + " ～ " + savedLeave.getEndDate()
                            + "\n区分: " + savedLeave.getLeaveType()
                            + "\n日数: " + savedLeave.getLeaveDays());
        } catch (RuntimeException exception) {
            logger.error("Annual leave was saved, but its notification could not be processed", exception);
            notificationResult = ApplicationNotificationService.Result.FAILED;
        }
        if (notificationResult != ApplicationNotificationService.Result.NOT_APPLICABLE) {
            redirectAttributes.addFlashAttribute("notificationStatus", notificationResult.name());
        }

        logger.debug("Saved leave with ID: {}, startDate: {}, endDate: {}",
                savedLeave.getId(), savedLeave.getStartDate(), savedLeave.getEndDate());
        logger.info("Successfully saved paid leave");
        return "redirect:/paid-leave";
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
