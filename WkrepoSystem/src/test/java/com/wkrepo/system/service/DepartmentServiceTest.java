package com.wkrepo.system.service;

import com.wkrepo.system.entity.Department;
import com.wkrepo.system.repository.DepartmentRepository;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 部署マスタ：選択できる部署のチェック、名称変更の所属ユーザーへの反映、削除の制限、初期登録 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepartmentServiceTest {

    @Mock
    private DepartmentRepository departmentRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DepartmentService service;

    private final List<Department> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stored.add(department(1L, "開発部", true));
        stored.add(department(2L, "旧部署", false));
        when(departmentRepository.findByName(anyString())).thenAnswer(invocation ->
                stored.stream().filter(d -> d.getName().equals(invocation.getArgument(0))).findFirst());
        when(departmentRepository.existsByName(anyString())).thenAnswer(invocation ->
                stored.stream().anyMatch(d -> d.getName().equals(invocation.getArgument(0))));
        when(departmentRepository.findById(any())).thenAnswer(invocation ->
                stored.stream().filter(d -> d.getId().equals(invocation.getArgument(0))).findFirst());
        when(departmentRepository.findAllByOrderBySortOrderAscNameAsc()).thenReturn(stored);
        when(departmentRepository.save(any(Department.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void onlyRegisteredActiveDepartmentCanBeAssigned() {
        assertThat(service.requireAssignable(" 開発部 ", null)).isEqualTo("開発部");
        assertThatThrownBy(() -> service.requireAssignable("开发部", null))
                .hasMessage("department.error.unknown");
        assertThatThrownBy(() -> service.requireAssignable("旧部署", "開発部"))
                .hasMessage("department.error.inactive");
        assertThatThrownBy(() -> service.requireAssignable("  ", null))
                .hasMessage("department.error.required");
    }

    @Test
    void userCanStayInCurrentDepartmentEvenIfDeactivated() {
        assertThat(service.requireAssignable("旧部署", "旧部署")).isEqualTo("旧部署");
    }

    @Test
    void renameUpdatesUsersToo() {
        when(userRepository.renameDepartment("開発部", "開発本部")).thenReturn(3);

        assertThat(service.rename(1L, " 開発本部 ")).isEqualTo(3);
        assertThat(stored.get(0).getName()).isEqualTo("開発本部");
    }

    @Test
    void renameToExistingNameIsRejected() {
        assertThatThrownBy(() -> service.rename(1L, "旧部署")).hasMessage("department.error.duplicate");
        verify(userRepository, never()).renameDepartment(anyString(), anyString());
    }

    @Test
    void departmentWithUsersCannotBeDeleted() {
        when(userRepository.countByDepartment("開発部")).thenReturn(2L);

        assertThatThrownBy(() -> service.delete(1L)).hasMessage("department.error.inUse");
        verify(departmentRepository, never()).delete(any());
    }

    @Test
    void duplicateDepartmentIsRejected() {
        assertThatThrownBy(() -> service.create("開発部")).hasMessage("department.error.duplicate");
        assertThat(service.create("営業部").getSortOrder()).isGreaterThan(0);
    }

    @Test
    void missingDepartmentsOfUsersAreRegisteredAtStartup() {
        when(departmentRepository.count()).thenReturn(2L); // 既にマスタがある（初期値は入れない）
        when(userRepository.findDistinctDepartments()).thenReturn(List.of("開発部", "営業部 ", "总务部"));

        assertThat(service.registerMissingDepartments()).isEqualTo(2);
        ArgumentCaptor<Department> saved = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Department::getName).containsExactlyInAnyOrder("営業部", "总务部");
    }

    @Test
    void defaultsAndUsedDepartmentsAreRegisteredWhenMasterIsEmpty() {
        stored.clear();
        when(userRepository.findDistinctDepartments()).thenReturn(List.of("開発部", "品質保証部"));
        when(departmentRepository.count()).thenReturn(0L);

        // 初期値 5 件（開発部を含む）＋ユーザーが使っている 品質保証部
        assertThat(service.registerMissingDepartments()).isEqualTo(DepartmentService.DEFAULT_DEPARTMENTS.size() + 1);
    }

    private static Department department(Long id, String name, boolean active) {
        Department department = new Department(name, id.intValue() * 10);
        department.setId(id);
        department.setActive(active);
        return department;
    }
}
