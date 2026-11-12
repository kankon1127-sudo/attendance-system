package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.Department;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.service.DepartmentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署マスタの管理（管理者のみ。/admin/ 配下なので PermissionInterceptor でも制限している）。
 * 部署名の変更は所属ユーザーにも反映する。所属ユーザーがいる部署は削除できず、無効化のみ。
 */
@Controller
@RequestMapping("/admin/departments")
public class DepartmentManagementController {

    private final DepartmentService departmentService;
    private final Messages messages;

    public DepartmentManagementController(DepartmentService departmentService, Messages messages) {
        this.departmentService = departmentService;
        this.messages = messages;
    }

    @GetMapping
    public String showDepartments(HttpSession session, Model model) {
        User user = (User) session.getAttribute("user");
        if (!isAdmin(user)) {
            return "redirect:/home?error=permission";
        }
        List<Department> departments = departmentService.findAll();
        Map<Long, Long> userCounts = new LinkedHashMap<>();
        for (Department department : departments) {
            userCounts.put(department.getId(), departmentService.countUsers(department.getName()));
        }
        model.addAttribute("departments", departments);
        model.addAttribute("userCounts", userCounts);
        model.addAttribute("user", user);
        return "admin-departments";
    }

    @PostMapping("/add")
    public String add(@RequestParam(name = "name", required = false) String name,
                      HttpSession session, RedirectAttributes redirectAttributes) {
        if (!isAdmin((User) session.getAttribute("user"))) {
            return "redirect:/home?error=permission";
        }
        try {
            Department created = departmentService.create(name);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("department.added", created.getName()));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/admin/departments";
    }

    @PostMapping("/{id}/rename")
    public String rename(@PathVariable("id") Long id, @RequestParam(name = "name", required = false) String name,
                         HttpSession session, RedirectAttributes redirectAttributes) {
        if (!isAdmin((User) session.getAttribute("user"))) {
            return "redirect:/home?error=permission";
        }
        try {
            int updatedUsers = departmentService.rename(id, name);
            redirectAttributes.addFlashAttribute("successMessage",
                    messages.get("department.renamed", name == null ? "" : name.trim(), updatedUsers));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/admin/departments";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable("id") Long id, HttpSession session, RedirectAttributes redirectAttributes) {
        if (!isAdmin((User) session.getAttribute("user"))) {
            return "redirect:/home?error=permission";
        }
        try {
            Department department = departmentService.toggleActive(id);
            redirectAttributes.addFlashAttribute("successMessage", messages.get(
                    department.isActive() ? "department.activated" : "department.deactivated", department.getName()));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/admin/departments";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable("id") Long id, HttpSession session, RedirectAttributes redirectAttributes) {
        if (!isAdmin((User) session.getAttribute("user"))) {
            return "redirect:/home?error=permission";
        }
        try {
            departmentService.delete(id);
            redirectAttributes.addFlashAttribute("successMessage", messages.get("department.deleted"));
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/admin/departments";
    }

    private boolean isAdmin(User user) {
        return user != null && "ADMIN".equals(user.getRole());
    }
}
