package com.wkrepo.system.controller;

import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import com.wkrepo.system.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class UserManagementController {
    
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private DefaultAttendanceSettingService defaultAttendanceSettingService;

    @Autowired
    private Messages messages;

    @Autowired
    private com.wkrepo.system.service.DepartmentService departmentService;

    @GetMapping("/user-management")
    public String userManagement(
            @RequestParam(name = "searchName", required = false) String searchName,
            @RequestParam(name = "searchDepartment", required = false) String searchDepartment,
            HttpSession session, Model model) {
        
        User user = (User) session.getAttribute("user");
        if (user == null) {
            return "redirect:/login";
        }

        // 第 3 層：GENERAL ロールはホーム画面にリダイレクト
        if ("GENERAL".equals(user.getRole())) {
            return "redirect:/home";
        }

        List<User> allUsers = userRepository.findAll();
        
        List<User> accessibleUsers;
        String currentUserDept = user.getDepartment();
        boolean isAdmin = "ADMIN".equals(user.getRole());
        boolean isSoumu = "SOUMU".equals(user.getRole());
        boolean isManager = "MANAGER".equals(user.getRole());
        boolean canViewAll = isAdmin || isSoumu;
        
        if (canViewAll) {
            accessibleUsers = new ArrayList<>(allUsers);
        } else {
            accessibleUsers = allUsers.stream()
                    .filter(u -> currentUserDept != null && currentUserDept.equals(u.getDepartment()))
                    .collect(Collectors.toList());
        }

        List<User> filteredUsers = new ArrayList<>(accessibleUsers);
        
        if (searchName != null && !searchName.trim().isEmpty()) {
            final String keyword = searchName.trim();
            filteredUsers = filteredUsers.stream()
                    .filter(u -> containsIgnoreNull(u.getName(), keyword)
                            || containsIgnoreNull(u.getUsername(), keyword)
                            || containsIgnoreNull(u.getEmployeeId(), keyword))
                    .collect(Collectors.toList());
        }
        
        if (searchDepartment != null && !searchDepartment.trim().isEmpty()) {
            final String deptToSearch = searchDepartment.trim();
            filteredUsers = filteredUsers.stream()
                    .filter(u -> u.getDepartment() != null && u.getDepartment().equals(deptToSearch))
                    .collect(Collectors.toList());
        }

        List<User> allUsersForDropdown = canViewAll ? allUsers : accessibleUsers;
        
        List<User> managerCandidates = allUsersForDropdown.stream()
                .filter(u -> "MANAGER".equals(u.getRole()) || "ADMIN".equals(u.getRole()))
                .collect(Collectors.toList());

        Map<Long, String> userNamesById = new HashMap<>();
        for (User u : allUsers) {
            userNamesById.put(u.getId(), u.getName());
        }

        List<Map<String, Object>> managerOptions = new ArrayList<>();
        for (User m : managerCandidates) {
            Map<String, Object> opt = new HashMap<>();
            opt.put("id", m.getId());
            opt.put("name", m.getName());
            opt.put("employeeId", m.getEmployeeId());
            opt.put("department", m.getDepartment());
            managerOptions.add(opt);
        }

        int totalCount = accessibleUsers.size();
        int activeCount = (int) accessibleUsers.stream().filter(u -> "ACTIVE".equals(u.getStatus())).count();

        model.addAttribute("users", filteredUsers);
        model.addAttribute("allUsers", allUsersForDropdown);
        model.addAttribute("userNamesById", userNamesById);
        model.addAttribute("managerCandidates", managerCandidates);
        model.addAttribute("managerOptions", managerOptions);
        model.addAttribute("searchName", searchName);
        model.addAttribute("searchDepartment", searchDepartment);
        model.addAttribute("totalCount", totalCount);
        model.addAttribute("activeCount", activeCount);
        model.addAttribute("currentPage", "user-management");
        model.addAttribute("user", user);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("isSoumu", isSoumu);
        model.addAttribute("isManager", isManager);
        model.addAttribute("canManageAll", canViewAll);
        model.addAttribute("canEdit", true);
        // 部署の選択肢（部署マスタの有効な部署）。絞り込みは無効な部署も含める
        model.addAttribute("departmentOptions", departmentService.findActive());
        model.addAttribute("allDepartments", departmentService.findAll());
        return "user-management";
    }

    @PostMapping("/user-management/create")
    public String createUser(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam("name") String name,
            @RequestParam("employeeId") String employeeId,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam("department") String department,
            @RequestParam("role") String role,
            @RequestParam(value = "managerId", required = false) Long managerId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        User currentUser = (User) session.getAttribute("user");
        if (currentUser == null) {
            return "redirect:/login";
        }

        boolean isAdmin = "ADMIN".equals(currentUser.getRole());
        boolean isSoumu = "SOUMU".equals(currentUser.getRole());
        boolean isManager = "MANAGER".equals(currentUser.getRole());
        if (!isAdmin && !isSoumu && !isManager) {
            return "redirect:/home";
        }

        if (isManager) {
            department = currentUser.getDepartment();
            if (!"GENERAL".equals(role) && !"MANAGER".equals(role)) {
                redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.managerCreateRole"));
                return "redirect:/user-management";
            }
        }
        if (isSoumu && "ADMIN".equals(role)) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.soumuCreateAdmin"));
            return "redirect:/user-management";
        }

        try {
            // 部長は自部署固定（自部署が無効化されていても登録できる）
            department = departmentService.requireAssignable(department, isManager ? currentUser.getDepartment() : null);
            User newUser = new User();
            newUser.setUsername(username == null ? "" : username.trim());
            newUser.setPassword(password);
            newUser.setName(name == null ? "" : name.trim());
            newUser.setEmployeeId(employeeId == null ? "" : employeeId.trim());
            newUser.setEmail(normalizeEmail(email));
            newUser.setDepartment(department);
            newUser.setRole(role);
            newUser.setManagerId(managerId != null && managerId > 0 ? managerId : null);
            newUser.setStatus("ACTIVE");
            User saved = userService.createUser(newUser);
            defaultAttendanceSettingService.save(new DefaultAttendanceSetting(saved));
            redirectAttributes.addFlashAttribute("successMessage", messages.get("user.created", saved.getName()));
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
        }
        return "redirect:/user-management";
    }

    @PostMapping("/user-management/update")
    public String updateUser(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "employeeId", required = false) String employeeId,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "managerId", required = false) Long managerId,
            @RequestParam(name = "searchName", required = false) String searchName,
            @RequestParam(name = "searchDepartment", required = false) String searchDepartment,
            HttpSession session,
            RedirectAttributes redirectAttributes) {
        
        User currentUser = (User) session.getAttribute("user");
        if (currentUser == null) {
            return "redirect:/login";
        }

        boolean isAdmin = "ADMIN".equals(currentUser.getRole());
        boolean isSoumu = "SOUMU".equals(currentUser.getRole());
        boolean isManager = "MANAGER".equals(currentUser.getRole());
        
        if (!isAdmin && !isSoumu && !isManager) {
            return "redirect:/home";
        }

        User targetUser = userRepository.findById(userId).orElse(null);
        if (targetUser == null) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.notFound"));
            return buildListRedirect(searchName, searchDepartment);
        }

        if (!isAdmin && "ADMIN".equals(targetUser.getRole())) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.adminOnly"));
            return buildListRedirect(searchName, searchDepartment);
        }

        String finalDept;
        try {
            // 部署マスタにある有効な部署か、現在の所属部署のみ受け付ける
            finalDept = (department != null && !department.trim().isEmpty())
                    ? departmentService.requireAssignable(department, targetUser.getDepartment())
                    : targetUser.getDepartment();
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.of(e));
            return buildListRedirect(searchName, searchDepartment);
        }
        String finalRole = (role != null && !role.trim().isEmpty()) ? role : targetUser.getRole();

        if (isManager) {
            String currentUserDept = currentUser.getDepartment();
            if (currentUserDept == null || !currentUserDept.equals(targetUser.getDepartment())) {
                return buildListRedirect(searchName, searchDepartment);
            }
            if (!currentUserDept.equals(finalDept) || "ADMIN".equals(finalRole) || "SOUMU".equals(finalRole)) {
                return buildListRedirect(searchName, searchDepartment);
            }
            // 部長が編集できるのは自部署の一般社員と自分のみ。他の部長は変更できない（パスワードも含む）
            if (!currentUser.getId().equals(targetUser.getId()) && !"GENERAL".equals(targetUser.getRole())) {
                redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.managerEditScope"));
                return buildListRedirect(searchName, searchDepartment);
            }
        }
        if (isSoumu && "ADMIN".equals(finalRole) && !"ADMIN".equals(targetUser.getRole())) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.soumuGrantAdmin"));
            return buildListRedirect(searchName, searchDepartment);
        }

        // ユーザー名・社員番号の重複チェック（本人を除く）。どの項目を変更するよりも前に行う
        String newUsername = username == null ? "" : username.trim();
        if (!newUsername.isEmpty() && !newUsername.equals(targetUser.getUsername())
                && userRepository.existsByUsername(newUsername)) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.usernameExists", newUsername));
            return buildListRedirect(searchName, searchDepartment);
        }
        String newEmployeeId = employeeId == null ? "" : employeeId.trim();
        if (!newEmployeeId.isEmpty() && !newEmployeeId.equals(targetUser.getEmployeeId())
                && userRepository.existsByEmployeeId(newEmployeeId)) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.employeeIdExists", newEmployeeId));
            return buildListRedirect(searchName, searchDepartment);
        }

        if (managerId != null && managerId > 0) {
            User managerUser = userRepository.findById(managerId).orElse(null);
            if (managerUser == null) {
                return buildListRedirect(searchName, searchDepartment);
            }
            if (!finalDept.equals(managerUser.getDepartment())) {
                return buildListRedirect(searchName, searchDepartment);
            }
            if (!"MANAGER".equals(managerUser.getRole()) && !"ADMIN".equals(managerUser.getRole())) {
                return buildListRedirect(searchName, searchDepartment);
            }
            targetUser.setManagerId(managerId);
        } else if (managerId != null) {
            targetUser.setManagerId(null);
        }

        if (name != null && !name.trim().isEmpty()) {
            targetUser.setName(name.trim());
        }
        if (username != null && !username.trim().isEmpty()) {
            targetUser.setUsername(username.trim());
        }
        if (employeeId != null && !employeeId.trim().isEmpty()) {
            targetUser.setEmployeeId(employeeId.trim());
        }
        if (email != null) {
            targetUser.setEmail(normalizeEmail(email));
        }
        if (password != null && !password.trim().isEmpty()) {
            try {
                userService.updatePassword(targetUser.getId(), password.trim());
            } catch (Exception e) {
                redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.passwordUpdateFailed", messages.of(e)));
                return buildListRedirect(searchName, searchDepartment);
            }
        }

        targetUser.setDepartment(finalDept);
        targetUser.setRole(finalRole);
        try {
            userRepository.save(targetUser);
        } catch (DataIntegrityViolationException e) {
            // 上記チェックの後に別のリクエストで先に使用された場合の保険
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.duplicate"));
            return buildListRedirect(searchName, searchDepartment);
        }
        redirectAttributes.addFlashAttribute("successMessage", messages.get("user.updated", targetUser.getName()));

        return buildListRedirect(searchName, searchDepartment);
    }

    @PostMapping("/user-management/toggle-status")
    public String toggleUserStatus(
            @RequestParam("userId") Long userId,
            @RequestParam(name = "searchName", required = false) String searchName,
            @RequestParam(name = "searchDepartment", required = false) String searchDepartment,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        User currentUser = (User) session.getAttribute("user");
        if (currentUser == null) {
            return "redirect:/login";
        }
        boolean isAdmin = "ADMIN".equals(currentUser.getRole());
        boolean isSoumu = "SOUMU".equals(currentUser.getRole());
        boolean isManager = "MANAGER".equals(currentUser.getRole());
        if (!isAdmin && !isSoumu && !isManager) {
            return "redirect:/home";
        }

        User targetUser = userRepository.findById(userId).orElse(null);
        if (targetUser == null) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.notFound"));
            return buildListRedirect(searchName, searchDepartment);
        }

        if (!isAdmin && "ADMIN".equals(targetUser.getRole())) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.adminOnly"));
            return buildListRedirect(searchName, searchDepartment);
        }
        if (isManager) {
            String currentUserDept = currentUser.getDepartment();
            if (currentUserDept == null || !currentUserDept.equals(targetUser.getDepartment())) {
                return buildListRedirect(searchName, searchDepartment);
            }
            if (!"GENERAL".equals(targetUser.getRole()) && !currentUser.getId().equals(targetUser.getId())) {
                redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.managerToggleScope"));
                return buildListRedirect(searchName, searchDepartment);
            }
        }
        if (currentUser.getId().equals(targetUser.getId())) {
            redirectAttributes.addFlashAttribute("errorMessage", messages.get("user.error.cannotDisableSelf"));
            return buildListRedirect(searchName, searchDepartment);
        }

        String next = "ACTIVE".equals(targetUser.getStatus()) ? "INACTIVE" : "ACTIVE";
        targetUser.setStatus(next);
        userRepository.save(targetUser);
        redirectAttributes.addFlashAttribute("successMessage",
                messages.get("user.statusChanged", targetUser.getName(),
                        messages.get("ACTIVE".equals(next) ? "user.active" : "user.inactive")));

        return buildListRedirect(searchName, searchDepartment);
    }

    private String buildListRedirect(String searchName, String searchDepartment) {
        StringBuilder redirectUrl = new StringBuilder("redirect:/user-management");
        boolean hasParam = false;
        if (searchName != null && !searchName.trim().isEmpty()) {
            redirectUrl.append(hasParam ? "&" : "?").append("searchName=").append(searchName);
            hasParam = true;
        }
        if (searchDepartment != null && !searchDepartment.trim().isEmpty()) {
            redirectUrl.append(hasParam ? "&" : "?").append("searchDepartment=").append(searchDepartment);
        }
        return redirectUrl.toString();
    }

    private String normalizeEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return null;
        }
        return email.trim();
    }

    private boolean containsIgnoreNull(String value, String keyword) {
        return value != null && value.contains(keyword);
    }
}
