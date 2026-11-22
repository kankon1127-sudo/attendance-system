package com.wkrepo.system.controller;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 社員管理：入社日・週の所定労働日数・時間の入力（送られない項目は変えない、空なら未設定） */
class UserWorkPatternTest {

    @Test
    void setsAndClearsWorkPattern() {
        User user = TestData.user(3L, "GENERAL", "開発部");

        UserManagementController.applyWorkPattern(user, "2024-04-01", "3", "20.5");
        assertThat(user.getHireDate()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(user.getWeeklyWorkDays()).isEqualTo(3);
        assertThat(user.getWeeklyWorkHours()).isEqualByComparingTo("20.5");

        // 送られない項目はそのまま
        UserManagementController.applyWorkPattern(user, null, null, null);
        assertThat(user.getHireDate()).isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(user.getWeeklyWorkDays()).isEqualTo(3);

        // 空なら未設定、5 日以上は通常扱い
        UserManagementController.applyWorkPattern(user, "", "5", " ");
        assertThat(user.getHireDate()).isNull();
        assertThat(user.getWeeklyWorkDays()).isNull();
        assertThat(user.getWeeklyWorkHours()).isNull();
    }

    @Test
    void rejectsInvalidValues() {
        User user = TestData.user(3L, "GENERAL", "開発部");
        user.setWeeklyWorkHours(new BigDecimal("20"));

        assertThatThrownBy(() -> UserManagementController.applyWorkPattern(user, "2024/04/01", null, null))
                .hasMessage("user.error.invalidHireDate");
        assertThatThrownBy(() -> UserManagementController.applyWorkPattern(user, null, "0", null))
                .hasMessage("user.error.invalidWorkPattern");
        assertThatThrownBy(() -> UserManagementController.applyWorkPattern(user, null, "abc", null))
                .hasMessage("user.error.invalidWorkPattern");
        assertThatThrownBy(() -> UserManagementController.applyWorkPattern(user, null, null, "-1"))
                .hasMessage("user.error.invalidWorkPattern");
        assertThat(user.getWeeklyWorkHours()).isEqualByComparingTo("20");
    }
}
