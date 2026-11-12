package com.wkrepo.system.controller;

import com.wkrepo.system.TestData;
import com.wkrepo.system.config.Messages;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.service.ApplicationListService;
import com.wkrepo.system.service.ApplicationNotificationService;
import com.wkrepo.system.service.ApprovalHistoryService;
import com.wkrepo.system.service.MonthlyReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 一括承認：1 件ずつ権限を確認し、承認できないものは飛ばす */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApplicationListControllerTest {

    @Mock
    private ApplicationListService applicationListService;
    @Mock
    private MonthlyReportService monthlyReportService;
    @Mock
    private ApprovalHistoryService approvalHistoryService;
    @Mock
    private ApplicationNotificationService applicationNotificationService;
    @Spy
    private Messages messages = TestData.messages();

    @InjectMocks
    private ApplicationListController controller;

    private final User manager = TestData.user(2L, "MANAGER", "開発部");

    @Test
    void bulkApprovalApprovesOnlyPermittedItems() {
        when(applicationListService.canApproveApplication(10L, "PAID_LEAVE", manager)).thenReturn(true);
        when(applicationListService.canApproveApplication(11L, "TRAVEL", manager)).thenReturn(false);
        when(approvalHistoryService.transition(anyString(), anyLong(), any(), any()))
                .thenReturn(new ApprovalHistoryService.Transition(TestData.user(10L, "GENERAL", "開発部"), "年休"));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.approveBulk(List.of("PAID_LEAVE:10", "TRAVEL:11", "broken", "GENERAL:x"),
                "PAID_LEAVE", "PENDING", session(), redirect);

        verify(approvalHistoryService, times(1)).transition(eq("PAID_LEAVE"), eq(10L), eq(ApplicationStatus.APPROVED), eq(manager));
        assertThat((String) redirect.getFlashAttributes().get("bulkMessage")).contains("1");
        assertThat((String) redirect.getFlashAttributes().get("errorMessage")).contains("3");
        assertThat(view).isEqualTo("redirect:/application/list?filterType=PAID_LEAVE&filterStatus=PENDING");
    }

    @Test
    void unsafeFilterValuesAreNotPutIntoRedirect() {
        String view = controller.approveBulk(List.of(), "X\r\nLocation: http://evil", "pending",
                session(), new RedirectAttributesModelMap());

        assertThat(view).isEqualTo("redirect:/application/list");
        verify(approvalHistoryService, never()).transition(anyString(), anyLong(), any(), any());
    }

    private MockHttpSession session() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", manager);
        return session;
    }
}
