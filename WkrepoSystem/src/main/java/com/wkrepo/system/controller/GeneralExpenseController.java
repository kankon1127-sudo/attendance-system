package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.ApplicationNotificationService;
import com.wkrepo.system.service.AttendanceService;
import com.wkrepo.system.service.GeneralExpenseService;
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
import java.time.YearMonth;
import java.util.List;

@Controller
@RequestMapping("/general-expense")
public class GeneralExpenseController {
    
    private static final Logger logger = LoggerFactory.getLogger(GeneralExpenseController.class);
    
    @Autowired
    private GeneralExpenseService generalExpenseService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private ApplicationNotificationService applicationNotificationService;

    @Autowired
    private Messages messages;
    
    @GetMapping
    public String showGeneralExpense(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            HttpSession session, Model model) {
        
        logger.info("Entering showGeneralExpense - year: {}, month: {}", year, month);
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            logger.warn("User not authenticated, redirecting to login");
            return "redirect:/login";
        }
        
        LocalDate today = LocalDate.now();
        int currentYear = (year != null) ? year : today.getYear();
        int currentMonth = (month != null) ? month : today.getMonthValue();
        
        logger.debug("Processing for user: {}, year: {}, month: {}", user.getId(), currentYear, currentMonth);
        
        YearMonth yearMonth = YearMonth.of(currentYear, currentMonth);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();
        
        List<GeneralExpense> expenses = generalExpenseService.getGeneralExpensesByUserAndDateRange(user, startDate, endDate);
        
        logger.debug("Retrieved {} expenses for user {} in period {}-{}", expenses.size(), user.getId(), currentYear, currentMonth);
        
        BigDecimal totalAmount = sumAmounts(expenses);
        
        model.addAttribute("expenses", expenses);
        model.addAttribute("totalAmount", totalAmount);
        model.addAttribute("currentYear", currentYear);
        model.addAttribute("currentMonth", currentMonth);
        model.addAttribute("user", user);
        model.addAttribute("expenseLocked", attendanceService.isMonthLocked(user, currentYear, currentMonth));
        
        model.addAttribute("newExpense", new GeneralExpense());
        
        // Generate years for dropdown
        List<Integer> availableYears = generateAvailableYears(today);
        model.addAttribute("availableYears", availableYears);
        
        // ページングボタンの処理
        YearMonth prev = yearMonth.minusMonths(1);
        YearMonth next = yearMonth.plusMonths(1);
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());
        model.addAttribute("showNextMonth", !yearMonth.isAfter(YearMonth.from(today)));
        
        logger.info("Successfully prepared model attributes for general expense view");
        
        return "general-expense";
    }
    
    @PostMapping("/save")
    public String saveGeneralExpense(
            @RequestParam("year") int year,
            @RequestParam("month") int month,
            GeneralExpense expense,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        
        logger.info("Entering saveGeneralExpense - type: {}, date: {}, amount: {}", 
                   expense.getExpenseType(), expense.getDate(), expense.getAmount());
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            logger.warn("User not authenticated, redirecting to login");
            return "redirect:/login";
        }
        
        try {
            expense.setUser(user);
            GeneralExpense savedExpense = generalExpenseService.saveGeneralExpense(expense);

            // 交通費と同様：一般社員の場合のみ上司に通知する。通知に失敗しても保存には影響しない
            ApplicationNotificationService.Result notificationResult;
            try {
                notificationResult = applicationNotificationService.notifyManager(
                        user,
                        "【Wkrepo】諸経費申請の承認依頼",
                        "日付: " + savedExpense.getDate()
                                + "\n種類: " + (savedExpense.getExpenseType() == null ? "-" : savedExpense.getExpenseType())
                                + "\n金額: " + (savedExpense.getAmount() == null ? "-" : savedExpense.getAmount()) + " 円");
            } catch (RuntimeException exception) {
                logger.error("General expense was saved, but its notification could not be processed", exception);
                notificationResult = ApplicationNotificationService.Result.FAILED;
            }
            if (notificationResult != ApplicationNotificationService.Result.NOT_APPLICABLE) {
                redirectAttributes.addFlashAttribute("notificationStatus", notificationResult.name());
            }

            logger.debug("Saved expense with ID: {}, date: {}, amount: {}", 
                        savedExpense.getId(), savedExpense.getDate(), savedExpense.getAmount());
            
            logger.info("Redirecting to year: {}, month: {} after saving expense", year, month);
            
            String redirectUrl = "/general-expense?year=" + year + "&month=" + month;
            
            return "redirect:" + redirectUrl;
        } catch (Exception e) {
            logger.error("Error saving general expense", e);
            redirectAttributes.addFlashAttribute("expenseErrorMessage", messages.of(e));
            return "redirect:/general-expense?year=" + year + "&month=" + month;
        }
    }
    
    @PostMapping("/delete/{id}")
    public String deleteGeneralExpense(
            @PathVariable Long id,
            @RequestParam("year") int year,
            @RequestParam("month") int month,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        logger.info("Deleting general expense with ID: {}", id);
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }
        
        try {
            generalExpenseService.deleteGeneralExpense(id, user);
            logger.info("Successfully deleted general expense with ID: {}", id);
        } catch (Exception e) {
            logger.error("Error deleting general expense with ID: {}", id, e);
            redirectAttributes.addFlashAttribute("expenseErrorMessage", messages.of(e));
        }
        
        return "redirect:/general-expense?year=" + year + "&month=" + month;
    }
    
    private List<Integer> generateAvailableYears(LocalDate today) {
        java.util.ArrayList<Integer> years = new java.util.ArrayList<>();
        int currentYear = today.getYear();
        for (int i = currentYear - 5; i <= currentYear; i++) {
            years.add(i);
        }
        return years;
    }
    
    private BigDecimal sumAmounts(List<GeneralExpense> expenses) {
        return expenses.stream()
                .map(GeneralExpense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
