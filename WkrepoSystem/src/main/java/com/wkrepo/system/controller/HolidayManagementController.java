package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.HolidayCsvImporter;
import com.wkrepo.system.service.HolidayService;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/**
 * 祝日管理（管理者のみ。/admin/ 配下なので PermissionInterceptor でも制限している）。
 * 国民の祝日はここで追加・削除し、会社休日は設定ファイルの内容を表示するだけ。
 */
@Controller
@RequestMapping("/admin/holidays")
public class HolidayManagementController {

    private final HolidayService holidayService;
    private final HolidayCsvImporter holidayCsvImporter;
    private final Messages messages;

    public HolidayManagementController(HolidayService holidayService, HolidayCsvImporter holidayCsvImporter,
                                       Messages messages) {
        this.holidayService = holidayService;
        this.holidayCsvImporter = holidayCsvImporter;
        this.messages = messages;
    }

    @GetMapping
    public String showHolidays(@RequestParam(name = "year", required = false) Integer year,
                               HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (!isAdmin(user)) {
            return "redirect:/home?error=permission";
        }
        int targetYear = year == null ? LocalDate.now().getYear() : year;
        model.addAttribute("year", targetYear);
        model.addAttribute("holidays", holidayService.getRegisteredHolidays(targetYear));
        model.addAttribute("companyHolidays", holidayService.getCompanyHolidays(targetYear));
        model.addAttribute("user", user);
        return "admin-holidays";
    }

    @PostMapping("/add")
    public String addHoliday(@RequestParam(name = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @RequestParam(name = "name", required = false) String name,
                             HttpSession session, RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (!isAdmin(user)) {
            return "redirect:/home?error=permission";
        }
        try {
            holidayService.addHoliday(date, name);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("holiday.added", date.toString(), name.strip()));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return redirectToYear(date);
    }

    @PostMapping("/delete/{id}")
    public String deleteHoliday(@PathVariable("id") Long id, @RequestParam("year") int year,
                                HttpSession session, RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (!isAdmin(user)) {
            return "redirect:/home?error=permission";
        }
        holidayService.deleteHoliday(id);
        redirectAttributes.addFlashAttribute("successMessage", messages.get("holiday.deleted"));
        return "redirect:/admin/holidays?year=" + year;
    }

    @PostMapping("/import")
    public String importHolidays(@RequestParam("year") int year, HttpSession session,
                                 RedirectAttributes redirectAttributes) {
        User user = (User) session.getAttribute("user");
        if (!isAdmin(user)) {
            return "redirect:/home?error=permission";
        }
        int imported = holidayCsvImporter.importMissing();
        redirectAttributes.addFlashAttribute("successMessage", messages.get("holiday.imported", imported));
        return "redirect:/admin/holidays?year=" + year;
    }

    private boolean isAdmin(User user) {
        return user != null && "ADMIN".equals(user.getRole());
    }

    private String redirectToYear(LocalDate date) {
        int year = date == null ? LocalDate.now().getYear() : date.getYear();
        return "redirect:/admin/holidays?year=" + year;
    }
}
