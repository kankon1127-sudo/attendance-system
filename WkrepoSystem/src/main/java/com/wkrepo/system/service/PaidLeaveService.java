package com.wkrepo.system.service;

import com.wkrepo.system.dto.PaidLeaveEntitlementSummary;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.PaidLeaveEntitlement;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.PaidLeaveEntitlementRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional
public class PaidLeaveService {

    private static final BigDecimal MAX_ANNUAL_GRANT = new BigDecimal("365.0");
    private static final List<ApplicationStatus> USED_STATUSES = List.of(
            ApplicationStatus.APPROVED,
            ApplicationStatus.MANAGER_APPROVED,
            ApplicationStatus.FINALIZED);
    private static final List<ApplicationStatus> PENDING_STATUSES = List.of(
            ApplicationStatus.PENDING,
            ApplicationStatus.SUBMITTED);

    private final PaidLeaveRepository paidLeaveRepository;
    private final PaidLeaveEntitlementRepository entitlementRepository;
    private final UserRepository userRepository;

    @Autowired
    public PaidLeaveService(
            PaidLeaveRepository paidLeaveRepository,
            PaidLeaveEntitlementRepository entitlementRepository,
            UserRepository userRepository) {
        this.paidLeaveRepository = paidLeaveRepository;
        this.entitlementRepository = entitlementRepository;
        this.userRepository = userRepository;
    }

    public PaidLeave savePaidLeave(PaidLeave paidLeave) {
        if (paidLeave.getStatus() == null) {
            paidLeave.setStatus(ApplicationStatus.PENDING);
        }
        return paidLeaveRepository.save(paidLeave);
    }
    
    public void deletePaidLeave(Long id) {
        paidLeaveRepository.deleteById(id);
    }
    
    public List<PaidLeave> getPaidLeavesByUser(User user) {
        return paidLeaveRepository.findByUserOrderByStartDateDesc(user);
    }
    
    public List<PaidLeave> getPaidLeavesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return paidLeaveRepository.findByUserAndStartDateBetweenOrderByStartDateAsc(user, startDate, endDate);
    }
    
    public PaidLeave getPaidLeaveById(Long id) {
        return paidLeaveRepository.findById(id).orElse(null);
    }
    
    public Double getTotalLeaveDaysByUserAndDateRange(Long userId, LocalDate startDate, LocalDate endDate) {
        return paidLeaveRepository.getTotalLeaveDaysByUserAndDateRange(userId, startDate, endDate);
    }
    
    public void updateStatus(Long id, ApplicationStatus status) {
        PaidLeave pl = getPaidLeaveById(id);
        if (pl != null) {
            pl.setStatus(status);
            paidLeaveRepository.save(pl);
        }
    }

    public List<PaidLeave> findLeavesCoveringDate(User user, LocalDate date) {
        return paidLeaveRepository.findByUserAndStartDateLessThanEqualAndEndDateGreaterThanEqual(user, date, date);
    }

    /**
     * 指定日にリーダー承認済みの年休があるか
     */
    public boolean isPaidLeaveApproved(User user, LocalDate date) {
        return findLeavesCoveringDate(user, date).stream().anyMatch(this::isLeaderApproved);
    }

    /**
     * 指定日に未承認（申請中）の年休があるか
     */
    public boolean hasPendingPaidLeave(User user, LocalDate date) {
        return findLeavesCoveringDate(user, date).stream().anyMatch(this::isPendingApproval);
    }

    public boolean isLeaderApproved(PaidLeave paidLeave) {
        if (paidLeave == null || paidLeave.getStatus() == null) {
            return false;
        }
        ApplicationStatus status = paidLeave.getStatus();
        return status == ApplicationStatus.APPROVED || status == ApplicationStatus.MANAGER_APPROVED;
    }

    private boolean isPendingApproval(PaidLeave paidLeave) {
        if (paidLeave == null || paidLeave.getStatus() == null) {
            return false;
        }
        ApplicationStatus status = paidLeave.getStatus();
        return status == ApplicationStatus.PENDING || status == ApplicationStatus.SUBMITTED;
    }

    @Transactional(readOnly = true)
    public Balance getBalance(User user, int year) {
        BigDecimal granted = entitlementRepository.findByUserIdAndYear(user.getId(), year)
                .map(PaidLeaveEntitlement::getGrantedDays)
                .orElse(BigDecimal.ZERO);

        LocalDate yearStart = Year.of(year).atDay(1);
        LocalDate yearEnd = YearMonth.of(year, 12).atEndOfMonth();
        List<PaidLeave> approvedLeaves = paidLeaveRepository
                .findByUserAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        user, USED_STATUSES, yearEnd, yearStart);
        List<PaidLeave> pendingLeaves = paidLeaveRepository
                .findByUserAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        user, PENDING_STATUSES, yearEnd, yearStart);

        BigDecimal used = sumDaysForYear(approvedLeaves, yearStart, yearEnd);
        BigDecimal pending = sumDaysForYear(pendingLeaves, yearStart, yearEnd);
        return new Balance(granted, used, granted.subtract(used), pending);
    }

    public void setAnnualEntitlement(Long userId, int year, BigDecimal grantedDays) {
        if (grantedDays == null || grantedDays.compareTo(BigDecimal.ZERO) < 0
                || grantedDays.compareTo(MAX_ANNUAL_GRANT) > 0
                || grantedDays.stripTrailingZeros().scale() > 1) {
            throw new IllegalArgumentException("leave.entitlement.invalid");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("leave.entitlement.userNotFound"));
        PaidLeaveEntitlement entitlement = entitlementRepository.findByUserIdAndYear(userId, year)
                .orElseGet(PaidLeaveEntitlement::new);
        entitlement.setUser(user);
        entitlement.setYear(year);
        entitlement.setGrantedDays(grantedDays.setScale(1, RoundingMode.UNNECESSARY));
        entitlementRepository.save(entitlement);
    }

    @Transactional(readOnly = true)
    public List<PaidLeaveEntitlementSummary> getEntitlementsForYear(int year) {
        Map<Long, BigDecimal> grantsByUser = new HashMap<>();
        entitlementRepository.findByYear(year).forEach(entitlement ->
                grantsByUser.put(entitlement.getUser().getId(), entitlement.getGrantedDays()));

        List<PaidLeaveEntitlementSummary> summaries = new ArrayList<>();
        userRepository.findAll().stream()
                .filter(user -> !"INACTIVE".equals(user.getStatus()))
                .forEach(user -> summaries.add(new PaidLeaveEntitlementSummary(
                        user.getId(),
                        user.getEmployeeId(),
                        user.getName(),
                        user.getDepartment(),
                        grantsByUser.getOrDefault(user.getId(), BigDecimal.ZERO.setScale(1)))));
        summaries.sort(Comparator.comparing(PaidLeaveEntitlementSummary::getDepartment,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(PaidLeaveEntitlementSummary::getEmployeeId,
                        Comparator.nullsLast(String::compareTo)));
        return summaries;
    }

    private BigDecimal sumDaysForYear(List<PaidLeave> leaves, LocalDate yearStart, LocalDate yearEnd) {
        return leaves.stream()
                .map(leave -> daysWithinYear(leave, yearStart, yearEnd))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(1, RoundingMode.HALF_UP);
    }

    private BigDecimal daysWithinYear(PaidLeave leave, LocalDate yearStart, LocalDate yearEnd) {
        if (leave.getStartDate() == null || leave.getEndDate() == null
                || leave.getLeaveDays() == null || leave.getEndDate().isBefore(leave.getStartDate())) {
            return null;
        }
        LocalDate overlapStart = leave.getStartDate().isAfter(yearStart) ? leave.getStartDate() : yearStart;
        LocalDate overlapEnd = leave.getEndDate().isBefore(yearEnd) ? leave.getEndDate() : yearEnd;
        if (overlapEnd.isBefore(overlapStart)) {
            return null;
        }
        long totalCalendarDays = ChronoUnit.DAYS.between(leave.getStartDate(), leave.getEndDate()) + 1;
        long calendarDaysInYear = ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
        return leave.getLeaveDays()
                .multiply(BigDecimal.valueOf(calendarDaysInYear))
                .divide(BigDecimal.valueOf(totalCalendarDays), 1, RoundingMode.HALF_UP);
    }

    public record Balance(BigDecimal grantedDays, BigDecimal usedDays,
                          BigDecimal remainingDays, BigDecimal pendingDays) {
    }
}
