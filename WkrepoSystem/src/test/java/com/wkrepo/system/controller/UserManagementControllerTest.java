package com.wkrepo.system.controller;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import com.wkrepo.system.service.DefaultAttendanceSettingService;
import com.wkrepo.system.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
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

/** 社员管理的权限规则（#16 管理员账号保护、#25 部长不能修改同级） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserManagementControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserService userService;
    @Mock
    private DefaultAttendanceSettingService defaultAttendanceSettingService;

    @InjectMocks
    private UserManagementController controller;

    // admin 与部长同部门，用来确认同部门的部长也不能修改 ADMIN
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

    // ---------- #16 只有 ADMIN 能修改 ADMIN ----------

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

    // ---------- #25 部长只能修改一般员工和自己 ----------

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
