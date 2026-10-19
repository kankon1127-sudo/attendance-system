package com.wkrepo.system.controller;

import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.GeneralExpenseService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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
        
        model.addAttribute("newExpense", new GeneralExpense());
        
        // Generate years for dropdown
        List<Integer> availableYears = generateAvailableYears(today);
        model.addAttribute("availableYears", availableYears);
        
        // 分页按钮逻辑
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
            HttpSession session) {
        
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
            
            logger.debug("Saved expense with ID: {}, date: {}, amount: {}", 
                        savedExpense.getId(), savedExpense.getDate(), savedExpense.getAmount());
            
            logger.info("Redirecting to year: {}, month: {} after saving expense", year, month);
            
            String redirectUrl = "/general-expense?year=" + year + "&month=" + month;
            
            return "redirect:" + redirectUrl;
        } catch (Exception e) {
            logger.error("Error saving general expense", e);
            return "redirect:/general-expense";
        }
    }
    
    @PostMapping("/delete/{id}")
    public String deleteGeneralExpense(
            @PathVariable Long id,
            @RequestParam("year") int year,
            @RequestParam("month") int month) {
        logger.info("Deleting general expense with ID: {}", id);
        
        try {
            generalExpenseService.deleteGeneralExpense(id);
            logger.info("Successfully deleted general expense with ID: {}", id);
        } catch (Exception e) {
            logger.error("Error deleting general expense with ID: {}", id, e);
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
