package com.wkrepo.system.service;

import com.wkrepo.system.entity.Department;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.DepartmentRepository;
import com.wkrepo.system.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 部署マスタ。users.department には部署名を保存し、登録・変更時はマスタにある名前だけを受け付ける。
 */
@Service
public class DepartmentService {

    private static final Logger logger = LoggerFactory.getLogger(DepartmentService.class);

    /** マスタが空で、ユーザーにも部署がない場合の初期値 */
    static final List<String> DEFAULT_DEPARTMENTS = List.of("管理部", "開発部", "営業部", "経理部", "総務部");
    static final int MAX_NAME_LENGTH = 50;

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;

    public DepartmentService(DepartmentRepository departmentRepository, UserRepository userRepository) {
        this.departmentRepository = departmentRepository;
        this.userRepository = userRepository;
    }

    public List<Department> findAll() {
        return departmentRepository.findAllByOrderBySortOrderAscNameAsc();
    }

    /** 選択肢用：有効な部署 */
    public List<Department> findActive() {
        return departmentRepository.findByActiveTrueOrderBySortOrderAscNameAsc();
    }

    public long countUsers(String departmentName) {
        return userRepository.countByDepartment(departmentName);
    }

    /**
     * ユーザーに設定する部署名をチェックする。有効な部署であること。
     * ただし、そのユーザーが既に所属している部署（無効化された部署を含む）はそのまま保存できる。
     */
    public String requireAssignable(String name, String currentDepartment) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("department.error.required");
        }
        if (trimmed.equals(currentDepartment)) {
            return trimmed;
        }
        Department department = departmentRepository.findByName(trimmed)
                .orElseThrow(() -> new BusinessException("department.error.unknown", trimmed));
        if (!department.isActive()) {
            throw new BusinessException("department.error.inactive", trimmed);
        }
        return trimmed;
    }

    @Transactional
    public Department create(String name) {
        String trimmed = validateName(name);
        if (departmentRepository.existsByName(trimmed)) {
            throw new BusinessException("department.error.duplicate", trimmed);
        }
        int nextOrder = findAll().stream().mapToInt(Department::getSortOrder).max().orElse(0) + 10;
        return departmentRepository.save(new Department(trimmed, nextOrder));
    }

    /** 部署名を変更し、所属ユーザーの部署名もまとめて変更する */
    @Transactional
    public int rename(Long id, String newName) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("department.error.notFound"));
        String trimmed = validateName(newName);
        if (trimmed.equals(department.getName())) {
            return 0;
        }
        if (departmentRepository.existsByName(trimmed)) {
            throw new BusinessException("department.error.duplicate", trimmed);
        }
        String oldName = department.getName();
        department.setName(trimmed);
        departmentRepository.save(department);
        int updatedUsers = userRepository.renameDepartment(oldName, trimmed);
        logger.info("部署名を変更しました：{} → {}（所属ユーザー {} 名）", oldName, trimmed, updatedUsers);
        return updatedUsers;
    }

    @Transactional
    public Department toggleActive(Long id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("department.error.notFound"));
        department.setActive(!department.isActive());
        return departmentRepository.save(department);
    }

    @Transactional
    public void updateSortOrder(Long id, int sortOrder) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("department.error.notFound"));
        department.setSortOrder(sortOrder);
        departmentRepository.save(department);
    }

    /** 所属ユーザーがいない部署だけ削除できる（いる場合は無効化する） */
    @Transactional
    public void delete(Long id) {
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("department.error.notFound"));
        long users = userRepository.countByDepartment(department.getName());
        if (users > 0) {
            throw new BusinessException("department.error.inUse", department.getName(), users);
        }
        departmentRepository.delete(department);
    }

    /**
     * 起動時：ユーザーが使っている部署名でマスタにないものを登録する（マスタ導入前のデータ用）。
     * マスタが空のとき（初回）は、初期値の部署を先に登録する。登録した件数を返す。
     */
    @Transactional
    public int registerMissingDepartments() {
        List<String> usedNames = userRepository.findDistinctDepartments().stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .sorted()
                .toList();
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (departmentRepository.count() == 0) {
            names.addAll(DEFAULT_DEPARTMENTS);
        }
        names.addAll(usedNames);
        int order = findAll().stream().mapToInt(Department::getSortOrder).max().orElse(0);
        int added = 0;
        for (String name : names) {
            if (!departmentRepository.existsByName(name) && name.length() <= MAX_NAME_LENGTH) {
                order += 10;
                departmentRepository.save(new Department(name, order));
                added++;
            }
        }
        return added;
    }

    private String validateName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("department.error.required");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("department.error.tooLong", MAX_NAME_LENGTH);
        }
        return trimmed;
    }
}
