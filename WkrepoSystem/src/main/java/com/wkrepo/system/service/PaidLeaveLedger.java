package com.wkrepo.system.service;

import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveGrant;
import com.wkrepo.system.enums.ApplicationStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 年休の台帳：申請（承認済み・申請中）を、取得開始日に有効な付与のうち古いもの（有効期限が早いもの）から順に充てる。
 * 時効になった付与の残りは使えない。
 */
public final class PaidLeaveLedger {

    static final Set<ApplicationStatus> USED = Set.of(
            ApplicationStatus.APPROVED, ApplicationStatus.MANAGER_APPROVED, ApplicationStatus.FINALIZED);
    static final Set<ApplicationStatus> PENDING = Set.of(ApplicationStatus.PENDING, ApplicationStatus.SUBMITTED);

    private PaidLeaveLedger() {
    }

    /** 付与ごとの使用状況 */
    public static final class GrantUsage {
        private final PaidLeaveGrant grant;
        private BigDecimal usedDays = BigDecimal.ZERO;
        private BigDecimal pendingDays = BigDecimal.ZERO;

        GrantUsage(PaidLeaveGrant grant) {
            this.grant = grant;
        }

        public PaidLeaveGrant getGrant() {
            return grant;
        }

        public BigDecimal getUsedDays() {
            return usedDays.setScale(1);
        }

        public BigDecimal getPendingDays() {
            return pendingDays.setScale(1);
        }

        /** 残り（承認済み・申請中を除く） */
        public BigDecimal getRemainingDays() {
            return grant.getGrantedDays().subtract(usedDays).subtract(pendingDays).setScale(1);
        }

        public boolean isValidOn(LocalDate date) {
            return grant.isValidOn(date);
        }
    }

    /**
     * 割り当て結果。
     *
     * @param grants    付与ごとの使用状況（付与日順）
     * @param shortages 充てられなかった日数（申請ごと。残日数を超えた分）
     */
    public record Result(List<GrantUsage> grants, Map<PaidLeave, BigDecimal> shortages) {

        /** 指定日に有効な付与の合計（付与・使用・申請中・残り） */
        public Totals totalsOn(LocalDate date) {
            BigDecimal granted = BigDecimal.ZERO;
            BigDecimal used = BigDecimal.ZERO;
            BigDecimal pending = BigDecimal.ZERO;
            for (GrantUsage usage : grants) {
                if (usage.isValidOn(date)) {
                    granted = granted.add(usage.getGrant().getGrantedDays());
                    used = used.add(usage.usedDays);
                    pending = pending.add(usage.pendingDays);
                }
            }
            return new Totals(granted.setScale(1), used.setScale(1), pending.setScale(1));
        }

        public BigDecimal shortageOf(PaidLeave leave) {
            return shortages.getOrDefault(leave, BigDecimal.ZERO);
        }
    }

    /** 合計。remaining = 付与 − 使用（申請中は含めない）、available = remaining − 申請中 */
    public record Totals(BigDecimal granted, BigDecimal used, BigDecimal pending) {
        public BigDecimal remaining() {
            return granted.subtract(used);
        }

        public BigDecimal available() {
            return remaining().subtract(pending).max(BigDecimal.ZERO);
        }
    }

    public static Result allocate(List<PaidLeaveGrant> grants, List<PaidLeave> leaves) {
        List<GrantUsage> usages = grants.stream()
                .sorted(Comparator.comparing(PaidLeaveGrant::getGrantDate)
                        .thenComparing(PaidLeaveGrant::getExpiryDate)
                        .thenComparing(grant -> grant.getId() == null ? Long.MAX_VALUE : grant.getId()))
                .map(GrantUsage::new)
                .toList();
        Map<PaidLeave, BigDecimal> shortages = new LinkedHashMap<>();
        List<PaidLeave> ordered = new ArrayList<>(leaves);
        ordered.removeIf(leave -> leave.getStartDate() == null || leave.getLeaveDays() == null
                || (!USED.contains(leave.getStatus()) && !PENDING.contains(leave.getStatus())));
        ordered.sort(Comparator.comparing(PaidLeave::getStartDate)
                .thenComparing(leave -> leave.getId() == null ? Long.MAX_VALUE : leave.getId()));

        for (PaidLeave leave : ordered) {
            BigDecimal need = leave.getLeaveDays();
            boolean pending = PENDING.contains(leave.getStatus());
            for (GrantUsage usage : usages.stream()
                    .filter(u -> u.isValidOn(leave.getStartDate()))
                    .sorted(Comparator.comparing((GrantUsage u) -> u.getGrant().getExpiryDate()))
                    .toList()) {
                if (need.signum() <= 0) {
                    break;
                }
                BigDecimal free = usage.getRemainingDays();
                if (free.signum() <= 0) {
                    continue;
                }
                BigDecimal take = free.min(need);
                if (pending) {
                    usage.pendingDays = usage.pendingDays.add(take);
                } else {
                    usage.usedDays = usage.usedDays.add(take);
                }
                need = need.subtract(take);
            }
            if (need.signum() > 0) {
                shortages.put(leave, need.setScale(1));
            }
        }
        return new Result(usages, shortages);
    }
}
