package com.wkrepo.system.controller;

import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.TravelExpenseService;
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
@RequestMapping("/travel-expense")
public class TravelExpenseController {
    
    private static final Logger logger = LoggerFactory.getLogger(TravelExpenseController.class);
    
    private static final String TYPE_NON_TAXABLE = "非課税交通費";
    private static final String TYPE_TRAVEL = "旅費交通費";
    
    @Autowired
    private TravelExpenseService travelExpenseService;
    
    @GetMapping
    public String showTravelExpense(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month,
            HttpSession session, Model model) {
        
        logger.info("Entering showTravelExpense - year: {}, month: {}", year, month);
        
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
        
        List<TravelExpense> expenses = travelExpenseService.getTravelExpensesByUserAndDateRange(user, startDate, endDate);
        
        logger.debug("Retrieved {} expenses for user {} in period {}-{}", expenses.size(), user.getId(), currentYear, currentMonth);
        
        List<TravelExpense> nonTaxableExpenses = expenses.stream()
                .filter(e -> TYPE_NON_TAXABLE.equals(e.getType()))
                .toList();
        List<TravelExpense> travelExpenses = expenses.stream()
                .filter(e -> TYPE_TRAVEL.equals(e.getType()))
                .toList();
        
        BigDecimal nonTaxableTotal = sumAmounts(nonTaxableExpenses);
        BigDecimal travelTotal = sumAmounts(travelExpenses);
        BigDecimal totalAmount = nonTaxableTotal.add(travelTotal);
        
        model.addAttribute("expenses", expenses);
        model.addAttribute("nonTaxableExpenses", nonTaxableExpenses);
        model.addAttribute("travelExpenses", travelExpenses);
        model.addAttribute("nonTaxableTotal", nonTaxableTotal);
        model.addAttribute("travelTotal", travelTotal);
        model.addAttribute("totalAmount", totalAmount);
        model.addAttribute("currentYear", currentYear);
        model.addAttribute("currentMonth", currentMonth);
        model.addAttribute("user", user);
        
        TravelExpense newNonTaxableExpense = new TravelExpense();
        newNonTaxableExpense.setType(TYPE_NON_TAXABLE);
        TravelExpense newTravelExpense = new TravelExpense();
        newTravelExpense.setType(TYPE_TRAVEL);
        model.addAttribute("newNonTaxableExpense", newNonTaxableExpense);
        model.addAttribute("newTravelExpense", newTravelExpense);
        
        // Generate months for dropdown
        YearMonth currentYearMonthForModel = YearMonth.from(today);
        YearMonth startYearMonth = getCurrentOrPreviousYearMonth(expenses, today);
        if (startYearMonth == null) {
            startYearMonth = currentYearMonthForModel.minusMonths(12);
        }
        
        List<Integer> availableYears = generateAvailableYears(startYearMonth, currentYearMonthForModel);
        model.addAttribute("availableYears", availableYears);
        
        // 分页按钮逻辑
        YearMonth prev = yearMonth.minusMonths(1);
        YearMonth next = yearMonth.plusMonths(1);
        model.addAttribute("prevYear", prev.getYear());
        model.addAttribute("prevMonth", prev.getMonthValue());
        model.addAttribute("nextYear", next.getYear());
        model.addAttribute("nextMonth", next.getMonthValue());
        model.addAttribute("showNextMonth", !yearMonth.isAfter(YearMonth.from(today)));
        
        logger.info("Successfully prepared model attributes for travel expense view");
        
        return "travel-expense";
    }
    
    @PostMapping("/save")
    public String saveTravelExpense(TravelExpense expense, HttpSession session) {
        
        logger.info("Entering saveTravelExpense - type: {}, expense date: {}, amount: {}", 
                   expense.getType(), expense.getExpenseDate(), expense.getAmount());
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            logger.warn("User not authenticated, redirecting to login");
            return "redirect:/login";
        }
        
        if (!TYPE_NON_TAXABLE.equals(expense.getType()) && !TYPE_TRAVEL.equals(expense.getType())) {
            logger.warn("Invalid travel expense type: {}", expense.getType());
            return "redirect:/travel-expense";
        }
        
        try {
            expense.setUser(user);
            TravelExpense savedExpense = travelExpenseService.saveTravelExpense(expense);
            
            logger.debug("Saved expense with ID: {}, date: {}, amount: {}", 
                        savedExpense.getId(), savedExpense.getExpenseDate(), savedExpense.getAmount());
            
            // 确保expenseDate存在，否则使用当前日期
            LocalDate effectiveDate = savedExpense.getExpenseDate();
            if (effectiveDate == null) {
                effectiveDate = LocalDate.now();
                logger.warn("Expense date was null, using current date: {}", effectiveDate);
            }
            
            Integer year = effectiveDate.getYear();
            Integer month = effectiveDate.getMonthValue();
            
            logger.info("Redirecting to year: {}, month: {} after saving expense", year, month);
            
            // 构建重定向URL，确保参数有效
            String redirectUrl = "/travel-expense?year=" + year + "&month=" + month;
            
            return "redirect:" + redirectUrl;
        } catch (Exception e) {
            logger.error("Error saving travel expense", e);
            return "redirect:/travel-expense";
        }
    }
    
    @PostMapping("/delete/{id}")
    public String deleteTravelExpense(@PathVariable Long id, HttpSession session) {
        logger.info("Deleting travel expense with ID: {}", id);
        
        LocalDate effectiveDate = LocalDate.now();
        try {
            TravelExpense expense = travelExpenseService.findById(id);
            if (expense != null && expense.getExpenseDate() != null) {
                effectiveDate = expense.getExpenseDate();
            }
            travelExpenseService.deleteTravelExpense(id);
            logger.info("Successfully deleted travel expense with ID: {}", id);
        } catch (Exception e) {
            logger.error("Error deleting travel expense with ID: {}", id, e);
        }
        
        Integer year = effectiveDate.getYear();
        Integer month = effectiveDate.getMonthValue();
        return "redirect:/travel-expense?year=" + year + "&month=" + month;
    }
    
    private YearMonth getCurrentOrPreviousYearMonth(List<TravelExpense> expenses, LocalDate today) {
        if (expenses != null && !expenses.isEmpty()) {
            LocalDate earliestDate = expenses.stream()
                    .map(TravelExpense::getExpenseDate)
                    .min(LocalDate::compareTo)
                    .orElse(null);
            if (earliestDate != null) {
                return YearMonth.from(earliestDate);
            }
        }
        return YearMonth.from(today).minusMonths(12);
    }
    
    private List<Integer> generateAvailableYears(YearMonth startYearMonth, YearMonth endYearMonth) {
        java.util.ArrayList<Integer> years = new java.util.ArrayList<>();
        YearMonth current = startYearMonth;
        while (!current.isAfter(endYearMonth)) {
            if (!years.contains(current.getYear())) {
                years.add(current.getYear());
            }
            current = current.plusMonths(1);
        }
        return years;
    }
    
    private BigDecimal sumAmounts(List<TravelExpense> expenses) {
        return expenses.stream()
                .map(TravelExpense::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}