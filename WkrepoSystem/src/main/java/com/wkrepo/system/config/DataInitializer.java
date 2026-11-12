package com.wkrepo.system.config;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.repository.PaidLeaveEntitlementRepository;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.PaidLeaveService;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import com.wkrepo.system.service.UserService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 起動時のアカウント初期化。
 * - 管理者：DB に ADMIN が 1 件もない場合のみ作成する。dev モードではパスワードは 123456、
 *   それ以外は環境変数 APP_ADMIN_INITIAL_PASSWORD を使う（未設定なら作成しない）。
 * - テストアカウント（manager / employee1 / employee2 / soumu）：dev モードでのみ作成する。
 * - DB に残っている平文パスワードを BCrypt ハッシュに変換する。
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

    @Autowired
    private UserService userService;

    @Autowired
    private PaidLeaveService paidLeaveService;

    @Autowired
    private PaidLeaveEntitlementRepository entitlementRepository;

    @Autowired
    private com.wkrepo.system.service.DepartmentService departmentService;

    private static final java.math.BigDecimal DEMO_ANNUAL_LEAVE_DAYS = new java.math.BigDecimal("10");

    @PostConstruct
    public void init() {
        boolean devMode = environment.acceptsProfiles(Profiles.of("dev"));

        // 0. 平文パスワードをハッシュに変換（旧バージョンのデータ）
        int converted = userService.encodePlainTextPasswords();
        if (converted > 0) {
            logger.info("{} 件のアカウントの平文パスワードをハッシュに変換しました", converted);
        }

        // 既存の admin の不足項目を補完する
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

        // 1. 管理者が 1 人もいない場合に admin を作成
        if (!userRepository.existsByRole("ADMIN")) {
            createInitialAdmin(devMode);
        }

        // 2. テストアカウントは dev モードでのみ作成
        if (devMode) {
            createTestAccounts();
            grantDemoEntitlements();
        }

        // 3. 部署マスタ：ユーザーが使っている部署名でマスタにないものを登録する（マスタ導入前のデータ用）
        int addedDepartments = departmentService.registerMissingDepartments();
        if (addedDepartments > 0) {
            logger.info("部署マスタに {} 件の部署を登録しました", addedDepartments);
        }
    }

    /**
     * dev モード：テストアカウントに今年の年休を 10 日付与する（未設定の場合のみ）。
     * 年休申請は残日数を超えるとできないため、付与がないとデモで申請できない。
     */
    private void grantDemoEntitlements() {
        int year = java.time.Year.now().getValue();
        for (String username : new String[] {"admin", "manager", "employee1", "employee2", "soumu"}) {
            userRepository.findByUsername(username).ifPresent(user -> {
                if (entitlementRepository.findByUserIdAndYear(user.getId(), year).isEmpty()) {
                    paidLeaveService.setAnnualEntitlement(user.getId(), year, DEMO_ANNUAL_LEAVE_DAYS);
                    logger.info("【付与】テストアカウント {} に {} 年の年休 {} 日", username, year, DEMO_ANNUAL_LEAVE_DAYS);
                }
            });
        }
    }

    private void createInitialAdmin(boolean devMode) {
        String password = devMode ? DEV_PASSWORD : environment.getProperty(ADMIN_PASSWORD_ENV);
        if (password == null || password.trim().length() < 4) {
            logger.warn("DB に管理者アカウントがなく、有効な環境変数 {}（4 文字以上）も設定されていないため、管理者を作成しませんでした。"
                    + "この環境変数を設定してから再起動してください。", ADMIN_PASSWORD_ENV);
            return;
        }
        if (userRepository.existsByUsername("admin") || userRepository.existsByEmployeeId("EMP001")) {
            logger.warn("DB に管理者アカウントがありませんが、ユーザー名 admin または社員番号 EMP001 が他のアカウントで使用されているため、管理者を作成しませんでした。");
            return;
        }

        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(UserService.encodePassword(password.trim()));
        admin.setName("系统管理员");
        admin.setEmployeeId("EMP001");
        admin.setDepartment("管理部");
        admin.setRole("ADMIN");
        User savedAdmin = userRepository.save(admin);
        defaultAttendanceSettingService.save(new DefaultAttendanceSetting(savedAdmin));
        logger.info("【作成】管理者アカウント: admin");
    }

    private void createTestAccounts() {
        Long adminId = userRepository.findByUsername("admin").map(User::getId).orElse(null);

        // 部長
        Optional<User> managerOpt = userRepository.findByUsername("manager");
        Long managerId;
        if (managerOpt.isEmpty()) {
            managerId = createTestUser("manager", "佐藤部长", "EMP002", "開発部", "MANAGER", adminId);
        } else {
            managerId = managerOpt.get().getId();
        }

        // 一般社員
        if (!userRepository.existsByUsername("employee1")) {
            createTestUser("employee1", "山田太郎", "EMP003", "開発部", "GENERAL", managerId);
        }
        if (!userRepository.existsByUsername("employee2")) {
            createTestUser("employee2", "田中花子", "EMP004", "開発部", "GENERAL", managerId);
        }

        // 総務（月報最終承認）
        if (!userRepository.existsByUsername("soumu")) {
            createTestUser("soumu", "鈴木総務", "EMP005", "総務部", "SOUMU", adminId);
        }
    }

    private Long createTestUser(String username, String name, String employeeId,
                                String department, String role, Long managerId) {
        if (userRepository.existsByEmployeeId(employeeId)) {
            logger.warn("社員番号 {} は他のアカウントで使用されているため、テストアカウント {} をスキップします", employeeId, username);
            return null;
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(UserService.encodePassword(DEV_PASSWORD));
        user.setName(name);
        user.setEmployeeId(employeeId);
        user.setDepartment(department);
        user.setRole(role);
        user.setManagerId(managerId);
        User saved = userRepository.save(user);
        defaultAttendanceSettingService.save(new DefaultAttendanceSetting(saved));
        logger.info("【作成】テストアカウント: {}", username);
        return saved.getId();
    }
}
