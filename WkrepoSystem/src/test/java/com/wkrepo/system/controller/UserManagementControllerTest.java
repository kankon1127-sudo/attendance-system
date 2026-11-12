package com.wkrepo.system.controller;

import com.wkrepo.system.TestData;
import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import com.wkrepo.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 社員管理の権限ルール（#16 管理者アカウントの保護、#25 部長は同格の部長を変更できない）と重複チェック（#26） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserManagementControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserService userService;
    @Mock
    private DefaultAttendanceSettingService defaultAttendanceSettingService;
    @Spy
    private Messages messages = TestData.messages();
    @Mock
    private com.wkrepo.system.service.DepartmentService departmentService;

    @InjectMocks
    private UserManagementController controller;

    // admin は部長と同じ部署。同じ部署の部長でも ADMIN を変更できないことを確認するため
    private final User admin = TestData.user(1L, "ADMIN", "开发部");
    private final User manager = TestData.user(2L, "MANAGER", "开发部");
    private final User peerManager = TestData.user(3L, "MANAGER", "开发部");
    private final User employee = TestData.user(4L, "GENERAL", "开发部");
    private final User soumu = TestData.user(5L, "SOUMU", "総務部");

    @BeforeEach
    void setUp() {
        for (User user : new User[] {admin, manager, peerManager, employee, soumu}) {
            registered(user);
        }
    }

    // ---------- #16 ADMIN を変更できるのは ADMIN のみ ----------

    @Test
    void soumuCannotChangeAdminPasswordOrRole() {
        RedirectAttributesModelMap redirect = update(soumu, admin, "hacked1", "GENERAL");

        assertThat(redirect.getFlashAttributes()).containsKey("errorMessage");
        verify(userService, never()).updatePassword(anyLong(), anyString());
        assertThat(admin.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void soumuAndManagerCannotDisableAdmin() {
        toggle(soumu, admin);
        toggle(manager, admin);

        assertThat(admin.getStatus()).isEqualTo("ACTIVE");
        verify(userRepository, never()).save(any());
    }

    @Test
    void adminCanDisableAnotherAdmin() {
        User otherAdmin = registered(TestData.user(6L, "ADMIN", "管理部"));

        toggle(admin, otherAdmin);

        assertThat(otherAdmin.getStatus()).isEqualTo("INACTIVE");
    }

    // ---------- #25 部長が変更できるのは一般社員と自分のみ ----------

    @Test
    void managerCannotChangePeerManagerPassword() {
        RedirectAttributesModelMap redirect = update(manager, peerManager, "hacked1", null);

        assertThat(redirect.getFlashAttributes()).containsKey("errorMessage");
        verify(userService, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    void managerCannotDisablePeerManager() {
        toggle(manager, peerManager);

        assertThat(peerManager.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void managerCanEditGeneralEmployeeAndSelf() {
        update(manager, employee, "newpass1", null);
        verify(userService).updatePassword(employee.getId(), "newpass1");

        update(manager, manager, "mypass12", null);
        verify(userService).updatePassword(manager.getId(), "mypass12");
    }

    @Test
    void managerCanDisableGeneralEmployee() {
        toggle(manager, employee);

        assertThat(employee.getStatus()).isEqualTo("INACTIVE");
    }

    // ---------- #26 ユーザー名・社員番号の重複チェック ----------

    @Test
    void duplicateUsernameIsRejectedWithoutSaving() {
        when(userRepository.existsByUsername("user2")).thenReturn(true);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.updateUser(employee.getId(), null, "user2", null, null, null, null, null, null,
                null, null, sessionOf(admin), redirect);

        assertThat((String) redirect.getFlashAttributes().get("errorMessage")).contains("user2");
        assertThat(employee.getUsername()).isEqualTo("user4");
        verify(userRepository, never()).save(any());
    }

    @Test
    void duplicateEmployeeIdIsRejected() {
        when(userRepository.existsByEmployeeId("EMP2")).thenReturn(true);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.updateUser(employee.getId(), null, null, "EMP2", null, null, null, null, null,
                null, null, sessionOf(admin), redirect);

        assertThat(redirect.getFlashAttributes()).containsKey("errorMessage");
        verify(userRepository, never()).save(any());
    }

    @Test
    void keepingOwnUsernameIsNotADuplicate() {
        // 自分の現在のユーザー名をそのまま送っても重複扱いにしない
        when(userRepository.existsByUsername("user4")).thenReturn(true);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.updateUser(employee.getId(), "NewName", "user4", "EMP4", null, null, null, null, null,
                null, null, sessionOf(admin), redirect);

        assertThat(redirect.getFlashAttributes()).containsKey("successMessage");
        verify(userRepository).save(employee);
    }

    // ---------- #44 部署は部署マスタから ----------

    @Test
    void departmentOutsideMasterIsRejected() {
        when(departmentService.requireAssignable("存在しない部", "开发部"))
                .thenThrow(new IllegalArgumentException("department.error.unknown"));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.updateUser(employee.getId(), null, null, null, null, null, "存在しない部", null, null,
                null, null, sessionOf(admin), redirect);

        assertThat(redirect.getFlashAttributes()).containsKey("errorMessage");
        assertThat(employee.getDepartment()).isEqualTo("开发部");
        verify(userRepository, never()).save(any());
    }

    @Test
    void registeredDepartmentIsSaved() {
        when(departmentService.requireAssignable(" 営業部 ", "开发部")).thenReturn("営業部");
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.updateUser(employee.getId(), null, null, null, null, null, " 営業部 ", null, null,
                null, null, sessionOf(admin), redirect);

        assertThat(redirect.getFlashAttributes()).containsKey("successMessage");
        assertThat(employee.getDepartment()).isEqualTo("営業部");
    }

    @Test
    void uniqueConstraintViolationShowsErrorInsteadOf500() {
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        controller.updateUser(employee.getId(), null, "newname", null, null, null, null, null, null,
                null, null, sessionOf(admin), redirect);

        assertThat(redirect.getFlashAttributes()).containsKey("errorMessage");
    }

    @Test
    void generalUserIsRedirectedHome() {
        assertThat(controller.toggleUserStatus(manager.getId(), null, null, sessionOf(employee),
                new RedirectAttributesModelMap())).isEqualTo("redirect:/home");
    }

    private RedirectAttributesModelMap update(User actor, User target, String password, String role) {
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        controller.updateUser(target.getId(), null, null, null, null, password, null, role, null,
                null, null, sessionOf(actor), redirect);
        return redirect;
    }

    private void toggle(User actor, User target) {
        controller.toggleUserStatus(target.getId(), null, null, sessionOf(actor), new RedirectAttributesModelMap());
    }

    private MockHttpSession sessionOf(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        return session;
    }

    private User registered(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }
}
