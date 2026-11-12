package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 承認経路：代理承認者の設定のチェック、通知先（上司＋有効な代理承認者） */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalRouteServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    @Mock
    private UserRepository userRepository;

    private ApprovalRouteService service;

    private final User manager = TestData.user(2L, "MANAGER", "開発部");
    private final User delegate = TestData.user(3L, "MANAGER", "営業部");
    private final User general = TestData.user(10L, "GENERAL", "開発部");
    private final User soumu = TestData.user(5L, "SOUMU", "総務部");

    @BeforeEach
    void setUp() {
        service = new ApprovalRouteService(userRepository,
                Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
        general.setManagerId(manager.getId());
        for (User user : List.of(manager, delegate, general, soumu)) {
            when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        }
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void managerCanSetAnotherManagerAsDelegate() {
        service.setDelegate(manager, delegate.getId(), TODAY, TODAY.plusDays(4));

        assertThat(manager.getApprovalDelegateId()).isEqualTo(delegate.getId());
        assertThat(manager.isApprovalDelegateActiveOn(TODAY)).isTrue();
        assertThat(manager.isApprovalDelegateActiveOn(TODAY.plusDays(5))).isFalse();
    }

    @Test
    void invalidDelegateSettingsAreRejected() {
        assertThatThrownBy(() -> service.setDelegate(general, delegate.getId(), TODAY, TODAY))
                .hasMessage("delegate.error.notApprover");
        assertThatThrownBy(() -> service.setDelegate(manager, manager.getId(), TODAY, TODAY))
                .hasMessage("delegate.error.self");
        assertThatThrownBy(() -> service.setDelegate(manager, soumu.getId(), TODAY, TODAY))
                .hasMessage("delegate.error.invalidDelegate");
        assertThatThrownBy(() -> service.setDelegate(manager, delegate.getId(), TODAY, TODAY.minusDays(1)))
                .hasMessage("delegate.error.invalidPeriod");
        delegate.setStatus("INACTIVE");
        assertThatThrownBy(() -> service.setDelegate(manager, delegate.getId(), TODAY, TODAY))
                .hasMessage("delegate.error.invalidDelegate");
        verify(userRepository, never()).save(any());
    }

    @Test
    void clearingRemovesPeriodToo() {
        manager.setApprovalDelegateId(delegate.getId());
        manager.setApprovalDelegateFrom(TODAY);
        manager.setApprovalDelegateTo(TODAY);

        service.setDelegate(manager, null, null, null);

        assertThat(manager.getApprovalDelegateId()).isNull();
        assertThat(manager.getApprovalDelegateFrom()).isNull();
    }

    @Test
    void notificationGoesToManagerAndActiveDelegate() {
        assertThat(service.notificationTargets(general)).containsExactly(manager);

        manager.setApprovalDelegateId(delegate.getId());
        manager.setApprovalDelegateFrom(TODAY.minusDays(1));
        manager.setApprovalDelegateTo(TODAY);
        assertThat(service.notificationTargets(general)).containsExactly(manager, delegate);

        manager.setApprovalDelegateTo(TODAY.minusDays(1));
        assertThat(service.notificationTargets(general)).containsExactly(manager);
    }

    @Test
    void approvableApplicantsIncludeSubordinatesOfDelegators() {
        manager.setApprovalDelegateId(delegate.getId());
        manager.setApprovalDelegateFrom(TODAY);
        manager.setApprovalDelegateTo(TODAY);
        when(userRepository.findByApprovalDelegateId(delegate.getId())).thenReturn(List.of(manager));
        when(userRepository.findByManagerIdIn(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(0);
            return ids.contains(manager.getId()) ? List.of(general) : List.of();
        });

        assertThat(service.actingApproverIds(delegate)).containsExactlyInAnyOrder(delegate.getId(), manager.getId());
        assertThat(service.approvableApplicantIds(delegate)).containsExactly(general.getId());
        assertThat(service.canApproveFor(delegate, general)).isTrue();
        assertThat(service.canApproveFor(soumu, general)).isFalse();
    }
}
