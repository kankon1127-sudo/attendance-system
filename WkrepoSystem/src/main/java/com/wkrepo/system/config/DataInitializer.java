package com.wkrepo.system.config;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 启动时的账号初始化。
 * - 管理员：数据库里没有任何 ADMIN 时才创建。dev 模式下密码为 123456，
 *   否则使用环境变量 APP_ADMIN_INITIAL_PASSWORD（未设置则不创建）。
 * - 测试账号（manager / employee1 / employee2 / soumu）：只在 dev 模式下创建。
 */
@Component
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String ADMIN_PASSWORD_ENV = "APP_ADMIN_INITIAL_PASSWORD";
    private static final String DEV_PASSWORD = "123456";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DefaultAttendanceSettingService defaultAttendanceSettingService;

    @Autowired
    private Environment environment;

    @PostConstruct
    public void init() {
        boolean devMode = environment.acceptsProfiles(Profiles.of("dev"));

        // 为现有的 admin 补全字段
        Optional<User> adminOpt = userRepository.findByUsername("admin");
        if (adminOpt.isPresent()) {
            User admin = adminOpt.get();
            if (admin.getRole() == null || admin.getRole().trim().isEmpty()) {
                admin.setRole("ADMIN");
            }
            if (admin.getDepartment() == null || admin.getDepartment().trim().isEmpty()) {
                admin.setDepartment("管理部");
            }
            userRepository.save(admin);
        }

        // 1. 没有任何管理员时创建 admin
        if (!userRepository.existsByRole("ADMIN")) {
            createInitialAdmin(devMode);
        }

        // 2. 测试账号只在 dev 模式下创建
        if (devMode) {
            createTestAccounts();
        }
    }

    private void createInitialAdmin(boolean devMode) {
        String password = devMode ? DEV_PASSWORD : environment.getProperty(ADMIN_PASSWORD_ENV);
        if (password == null || password.trim().length() < 4) {
            logger.warn("数据库中没有管理员账号，且未设置有效的环境变量 {}（至少 4 个字符），未创建管理员。"
                    + "请设置该环境变量后重新启动。", ADMIN_PASSWORD_ENV);
            return;
        }
        if (userRepository.existsByUsername("admin") || userRepository.existsByEmployeeId("EMP001")) {
            logger.warn("数据库中没有管理员账号，但用户名 admin 或社员番号 EMP001 已被其他账号使用，未创建管理员。");
            return;
        }

        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(password.trim());
        admin.setName("系统管理员");
        admin.setEmployeeId("EMP001");
        admin.setDepartment("管理部");
        admin.setRole("ADMIN");
        User savedAdmin = userRepository.save(admin);
        defaultAttendanceSettingService.save(new DefaultAttendanceSetting(savedAdmin));
        logger.info("【创建】管理员账号: admin");
    }

    private void createTestAccounts() {
        Long adminId = userRepository.findByUsername("admin").map(User::getId).orElse(null);

        // 部门主管
        Optional<User> managerOpt = userRepository.findByUsername("manager");
        Long managerId;
        if (managerOpt.isEmpty()) {
            managerId = createTestUser("manager", "佐藤部长", "EMP002", "开发部", "MANAGER", adminId);
        } else {
            managerId = managerOpt.get().getId();
        }

        // 普通员工
        if (!userRepository.existsByUsername("employee1")) {
            createTestUser("employee1", "山田太郎", "EMP003", "开发部", "GENERAL", managerId);
        }
        if (!userRepository.existsByUsername("employee2")) {
            createTestUser("employee2", "田中花子", "EMP004", "开发部", "GENERAL", managerId);
        }

        // 総務（月報最終承認）
        if (!userRepository.existsByUsername("soumu")) {
            createTestUser("soumu", "鈴木総務", "EMP005", "総務部", "SOUMU", adminId);
        }
    }

    private Long createTestUser(String username, String name, String employeeId,
                                String department, String role, Long managerId) {
        if (userRepository.existsByEmployeeId(employeeId)) {
            logger.warn("社员番号 {} 已被其他账号使用，跳过测试账号 {}", employeeId, username);
            return null;
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(DEV_PASSWORD);
        user.setName(name);
        user.setEmployeeId(employeeId);
        user.setDepartment(department);
        user.setRole(role);
        user.setManagerId(managerId);
        User saved = userRepository.save(user);
        defaultAttendanceSettingService.save(new DefaultAttendanceSetting(saved));
        logger.info("【创建】测试账号: {}", username);
        return saved.getId();
    }
}
