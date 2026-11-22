package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveGrant;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 台帳：有効期限の早い付与から充てる、時効の付与は使えない、足りない分は不足として返す */
class PaidLeaveLedgerTest {

    private final User user = TestData.user(1L, "GENERAL", "開発部");

    @Test
    void usesGrantWithEarliestExpiryFirstAndSeparatesPending() {
        PaidLeaveGrant older = grant(1L, "2024-10-01", "4");
        PaidLeaveGrant newer = grant(2L, "2025-10-01", "10");

        PaidLeaveLedger.Result result = PaidLeaveLedger.allocate(List.of(newer, older), List.of(
                leave("2026-05-01", "3", ApplicationStatus.APPROVED),
                leave("2026-06-01", "2", ApplicationStatus.PENDING),
                leave("2026-06-10", "1", ApplicationStatus.REJECTED)));

        assertThat(result.grants()).extracting(PaidLeaveLedger.GrantUsage::getGrant).containsExactly(older, newer);
        assertThat(result.grants().get(0).getUsedDays()).isEqualByComparingTo("3");
        assertThat(result.grants().get(0).getPendingDays()).isEqualByComparingTo("1");
        assertThat(result.grants().get(1).getPendingDays()).isEqualByComparingTo("1");
        assertThat(result.grants().get(1).getRemainingDays()).isEqualByComparingTo("9");

        PaidLeaveLedger.Totals totals = result.totalsOn(LocalDate.of(2026, 7, 1));
        assertThat(totals.granted()).isEqualByComparingTo("14");
        assertThat(totals.used()).isEqualByComparingTo("3");
        assertThat(totals.pending()).isEqualByComparingTo("2");
        assertThat(totals.remaining()).isEqualByComparingTo("11");
        assertThat(totals.available()).isEqualByComparingTo("9");
    }

    @Test
    void expiredOrFutureGrantsAreNotUsed() {
        PaidLeaveGrant expired = grant(1L, "2024-04-01", "10");   // 2026/3/31 まで
        PaidLeaveGrant future = grant(2L, "2026-12-01", "10");
        PaidLeave leave = leave("2026-11-10", "1", ApplicationStatus.APPROVED);

        PaidLeaveLedger.Result result = PaidLeaveLedger.allocate(List.of(expired, future), List.of(leave));

        assertThat(result.shortageOf(leave)).isEqualByComparingTo("1");
        assertThat(result.totalsOn(LocalDate.of(2026, 11, 10)).granted()).isEqualByComparingTo("0");
    }

    @Test
    void shortageIsPartial() {
        PaidLeave leave = leave("2026-11-10", "3", ApplicationStatus.PENDING);

        PaidLeaveLedger.Result result = PaidLeaveLedger.allocate(List.of(grant(1L, "2025-10-01", "1.5")), List.of(leave));

        assertThat(result.shortageOf(leave)).isEqualByComparingTo("1.5");
        assertThat(result.grants().get(0).getRemainingDays()).isEqualByComparingTo("0");
    }

    private PaidLeaveGrant grant(Long id, String grantDate, String days) {
        PaidLeaveGrant grant = new PaidLeaveGrant();
        grant.setId(id);
        grant.setUser(user);
        grant.setGrantDate(LocalDate.parse(grantDate));
        grant.setExpiryDate(LeaveGrantPolicy.expiryOf(LocalDate.parse(grantDate)));
        grant.setGrantedDays(new BigDecimal(days));
        grant.setSource(PaidLeaveGrant.SOURCE_AUTO);
        return grant;
    }

    private PaidLeave leave(String start, String days, ApplicationStatus status) {
        PaidLeave leave = new PaidLeave();
        leave.setUser(user);
        leave.setStartDate(LocalDate.parse(start));
        leave.setEndDate(LocalDate.parse(start));
        leave.setLeaveDays(new BigDecimal(days));
        leave.setStatus(status);
        return leave;
    }
}
