package com.wkrepo.system.interceptor;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionInterceptorTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PermissionInterceptor interceptor;

    @Test
    void redirectsToLoginWithoutSession() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", "/home"), response, null)).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
    }

    @Test
    void inactiveUserIsLoggedOutOnNextRequest() throws Exception {
        User sessionUser = TestData.user(3L, "GENERAL", "开发部");
        User current = TestData.user(3L, "GENERAL", "开发部");
        current.setStatus("INACTIVE");
        when(userRepository.findById(3L)).thenReturn(Optional.of(current));
        MockHttpSession session = sessionWith(sessionUser);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/home", session), response, null)).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void deletedUserIsLoggedOut() throws Exception {
        when(userRepository.findById(3L)).thenReturn(Optional.empty());
        MockHttpSession session = sessionWith(TestData.user(3L, "GENERAL", "开发部"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/home", session), response, null)).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void roleChangeTakesEffectImmediately() throws Exception {
        User demoted = TestData.user(2L, "GENERAL", "开发部");
        when(userRepository.findById(2L)).thenReturn(Optional.of(demoted));
        MockHttpSession session = sessionWith(TestData.user(2L, "MANAGER", "开发部"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("/user-management", session), response, null)).isFalse();
        assertThat(response.getRedirectedUrl()).isEqualTo("/home?error=permission");
        assertThat(((User) session.getAttribute("user")).getRole()).isEqualTo("GENERAL");
    }

    @Test
    void userManagementAllowsManagerSoumuAndAdminOnly() throws Exception {
        assertThat(allowed("GENERAL", "/user-management")).isFalse();
        assertThat(allowed("GENERAL", "/user-management/toggle-status")).isFalse();
        assertThat(allowed("MANAGER", "/user-management")).isTrue();
        assertThat(allowed("SOUMU", "/user-management")).isTrue();
        assertThat(allowed("ADMIN", "/user-management")).isTrue();
    }

    @Test
    void adminPathsRequireAdmin() throws Exception {
        assertThat(allowed("SOUMU", "/admin/database-backup")).isFalse();
        assertThat(allowed("MANAGER", "/admin/database-restore")).isFalse();
        assertThat(allowed("ADMIN", "/admin/database-backup")).isTrue();
        assertThat(allowed("ADMIN", "/admin/database-restore")).isTrue();
        assertThat(allowed("GENERAL", "/attendance")).isTrue();
    }

    @Test
    void everyoneCanEditOwnDefaultAttendanceSetting() throws Exception {
        assertThat(allowed("GENERAL", "/default-attendance-setting")).isTrue();
        assertThat(allowed("GENERAL", "/default-attendance-setting/save")).isTrue();
        assertThat(allowed("MANAGER", "/default-attendance-setting")).isTrue();
    }

    private boolean allowed(String role, String uri) throws Exception {
        User user = TestData.user(7L, role, "开发部");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        return interceptor.preHandle(request(uri, sessionWith(user)), new MockHttpServletResponse(), null);
    }

    private MockHttpSession sessionWith(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        return session;
    }

    private MockHttpServletRequest request(String uri, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setSession(session);
        return request;
    }
}
