package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.PaidLeaveEntitlementRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 年休申請の削除：本人のみ、かつ 申請中 / 拒否 の申請に限る */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaidLeaveServiceTest {

    @Mock
    private PaidLeaveRepository paidLeaveRepository;
    @Mock
    private PaidLeaveEntitlementRepository entitlementRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PaidLeaveService paidLeaveService;

    private final User owner = TestData.user(1L, "GENERAL", "开发部");
    private final User other = TestData.user(2L, "GENERAL", "开发部");

    @Test
    void ownerCanDeletePendingAndRejectedLeave() {
        PaidLeave pending = leave(ApplicationStatus.PENDING);
        paidLeaveService.deletePaidLeave(100L, owner);
        verify(paidLeaveRepository).delete(pending);

        PaidLeave rejected = leave(ApplicationStatus.REJECTED);
        paidLeaveService.deletePaidLeave(100L, owner);
        verify(paidLeaveRepository).delete(rejected);
    }

    @Test
    void otherUserCannotDelete() {
        leave(ApplicationStatus.PENDING);

        assertThatThrownBy(() -> paidLeaveService.deletePaidLeave(100L, other))
                .isInstanceOf(SecurityException.class);
        verify(paidLeaveRepository, never()).delete(any());
    }

    @Test
    void approvedLeaveCannotBeDeleted() {
        leave(ApplicationStatus.APPROVED);

        assertThatThrownBy(() -> paidLeaveService.deletePaidLeave(100L, owner))
                .isInstanceOf(IllegalStateException.class);
        verify(paidLeaveRepository, never()).delete(any());
    }

    @Test
    void missingLeaveIsReported() {
        when(paidLeaveRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paidLeaveService.deletePaidLeave(999L, owner))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PaidLeave leave(ApplicationStatus status) {
        PaidLeave leave = new PaidLeave();
        leave.setUser(owner);
        leave.setStatus(status);
        when(paidLeaveRepository.findById(100L)).thenReturn(Optional.of(leave));
        return leave;
    }
}
