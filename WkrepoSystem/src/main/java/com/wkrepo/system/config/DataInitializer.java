package com.wkrepo.system.config;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DataInitializer {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private DefaultAttendanceSettingService defaultAttendanceSettingService;
    
    @PostConstruct
    public void init() {
        User savedAdmin = null;
        User savedManager = null;
        
        // 1. 检查并创建管理员
        Optional<User> adminOpt = userRepository.findByUsername("admin");
        if (adminOpt.isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword("123456");
            admin.setName("系统管理员");
            admin.setEmployeeId("EMP001");
            admin.setDepartment("管理部");
            admin.setRole("ADMIN");
            savedAdmin = userRepository.save(admin);
            defaultAttendanceSettingService.save(new DefaultAttendanceSetting(savedAdmin));
            System.out.println("【创建】管理员账号: admin");
        } else {
            savedAdmin = adminOpt.get();
            // 为现有的 admin 补全字段
            if (savedAdmin.getRole() == null || savedAdmin.getRole().trim().isEmpty()) {
                savedAdmin.setRole("ADMIN");
            }
            if (savedAdmin.getDepartment() == null || savedAdmin.getDepartment().trim().isEmpty()) {
                savedAdmin.setDepartment("管理部");
            }
            userRepository.save(savedAdmin);
        }
        
        // 2. 检查并创建部门主管
        Optional<User> managerOpt = userRepository.findByUsername("manager");
        if (managerOpt.isEmpty()) {
            User manager = new User();
            manager.setUsername("manager");
            manager.setPassword("123456");
            manager.setName("佐藤部长");
            manager.setEmployeeId("EMP002");
            manager.setDepartment("开发部");
            manager.setRole("MANAGER");
            manager.setManagerId(savedAdmin.getId());
            savedManager = userRepository.save(manager);
            defaultAttendanceSettingService.save(new DefaultAttendanceSetting(savedManager));
            System.out.println("【创建】部门主管账号: manager");
        } else {
            savedManager = managerOpt.get();
        }
        
        // 3. 检查并创建普通员工1
        Optional<User> employee1Opt = userRepository.findByUsername("employee1");
        if (employee1Opt.isEmpty()) {
            User employee1 = new User();
            employee1.setUsername("employee1");
            employee1.setPassword("123456");
            employee1.setName("山田太郎");
            employee1.setEmployeeId("EMP003");
            employee1.setDepartment("开发部");
            employee1.setRole("GENERAL");
            employee1.setManagerId(savedManager.getId());
            User savedEmployee1 = userRepository.save(employee1);
            defaultAttendanceSettingService.save(new DefaultAttendanceSetting(savedEmployee1));
            System.out.println("【创建】普通员工1账号: employee1");
        }
        
        // 4. 检查并创建普通员工2
        Optional<User> employee2Opt = userRepository.findByUsername("employee2");
        if (employee2Opt.isEmpty()) {
            User employee2 = new User();
            employee2.setUsername("employee2");
            employee2.setPassword("123456");
            employee2.setName("田中花子");
            employee2.setEmployeeId("EMP004");
            employee2.setDepartment("开发部");
            employee2.setRole("GENERAL");
            employee2.setManagerId(savedManager.getId());
            User savedEmployee2 = userRepository.save(employee2);
            defaultAttendanceSettingService.save(new DefaultAttendanceSetting(savedEmployee2));
            System.out.println("【创建】普通员工2账号: employee2");
        }

        // 5. 総務（月報最終承認）
        Optional<User> soumuOpt = userRepository.findByUsername("soumu");
        if (soumuOpt.isEmpty()) {
            User soumu = new User();
            soumu.setUsername("soumu");
            soumu.setPassword("123456");
            soumu.setName("鈴木総務");
            soumu.setEmployeeId("EMP005");
            soumu.setDepartment("総務部");
            soumu.setRole("SOUMU");
            soumu.setManagerId(savedAdmin.getId());
            User savedSoumu = userRepository.save(soumu);
            defaultAttendanceSettingService.save(new DefaultAttendanceSetting(savedSoumu));
            System.out.println("【创建】総務账号: soumu");
        }

        System.out.println("========================================");
        System.out.println("所有测试账号就绪:");
        System.out.println("----------------------------------------");
        System.out.println("【管理员】");
        System.out.println("用户名: admin / 密码: 123456");
        System.out.println("员工ID: EMP001 / 部门: 管理部");
        System.out.println("----------------------------------------");
        System.out.println("【部门主管】");
        System.out.println("用户名: manager / 密码: 123456");
        System.out.println("员工ID: EMP002 / 部门: 开发部");
        System.out.println("----------------------------------------");
        System.out.println("【普通员工1】");
        System.out.println("用户名: employee1 / 密码: 123456");
        System.out.println("员工ID: EMP003 / 部门: 开发部");
        System.out.println("----------------------------------------");
        System.out.println("【普通员工2】");
        System.out.println("用户名: employee2 / 密码: 123456");
        System.out.println("员工ID: EMP004 / 部门: 开发部");
        System.out.println("----------------------------------------");
        System.out.println("【総務】");
        System.out.println("用户名: soumu / 密码: 123456");
        System.out.println("员工ID: EMP005 / 部门: 総務部");
        System.out.println("========================================");
    }
}