package com.wkrepo.system.service;

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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void encodedPasswordIsBcryptAndNotPlainText() {
        String encoded = UserService.encodePassword("123456");

        assertThat(encoded).isNotEqualTo("123456");
        assertThat(UserService.isEncoded(encoded)).isTrue();
        assertThat(UserService.isEncoded("123456")).isFalse();
        assertThat(UserService.isEncoded(null)).isFalse();
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        User user = userWithPassword(UserService.encodePassword("secret1"));

        assertThat(userService.login("user1", "secret1")).isSameAs(user);
    }

    @Test
    void loginFailsWithWrongPassword() {
        userWithPassword(UserService.encodePassword("secret1"));

        assertThatThrownBy(() -> userService.login("user1", "wrong"))
                .hasMessage("login.error.wrongPassword");
    }

    @Test
    void loginFailsForInactiveUser() {
        User user = userWithPassword(UserService.encodePassword("secret1"));
        user.setStatus("INACTIVE");

        assertThatThrownBy(() -> userService.login("user1", "secret1"))
                .hasMessage("login.error.inactive");
    }

    @Test
    void loginUpgradesLegacyPlainTextPassword() {
        User user = userWithPassword("legacy999");

        userService.login("user1", "legacy999");

        assertThat(UserService.isEncoded(user.getPassword())).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void wrongPasswordDoesNotUpgradeLegacyPassword() {
        User user = userWithPassword("legacy999");

        assertThatThrownBy(() -> userService.login("user1", "wrong"));
        assertThat(user.getPassword()).isEqualTo("legacy999");
        verify(userRepository, never()).save(any());
    }

    @Test
    void createUserStoresHash() {
        User user = TestData.user(9L, "GENERAL", "开发部");
        user.setPassword("pass9999");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User saved = userService.createUser(user);

        assertThat(UserService.isEncoded(saved.getPassword())).isTrue();
    }

    @Test
    void updatePasswordStoresHashAndRejectsShortPassword() {
        User user = userWithPassword(UserService.encodePassword("old1234"));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updatePassword(1L, "abc"));

        userService.updatePassword(1L, "new5678");
        assertThat(UserService.isEncoded(user.getPassword())).isTrue();
        assertThat(userService.login("user1", "new5678")).isSameAs(user);
    }

    @Test
    void encodePlainTextPasswordsConvertsOnlyPlainOnes() {
        User plain = TestData.user(1L, "GENERAL", "开发部");
        plain.setPassword("123456");
        User hashed = TestData.user(2L, "GENERAL", "开发部");
        hashed.setPassword(UserService.encodePassword("123456"));
        String originalHash = hashed.getPassword();
        when(userRepository.findAll()).thenReturn(List.of(plain, hashed));

        assertThat(userService.encodePlainTextPasswords()).isEqualTo(1);
        assertThat(UserService.isEncoded(plain.getPassword())).isTrue();
        assertThat(hashed.getPassword()).isEqualTo(originalHash);
    }

    private User userWithPassword(String storedPassword) {
        User user = TestData.user(1L, "GENERAL", "开发部");
        user.setPassword(storedPassword);
        when(userRepository.findByUsername("user1")).thenReturn(Optional.of(user));
        return user;
    }
}
