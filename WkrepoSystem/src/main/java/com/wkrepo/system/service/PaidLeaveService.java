package com.wkrepo.system.service;

import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.PaidLeaveRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class PaidLeaveService {
    
    @Autowired
    private PaidLeaveRepository paidLeaveRepository;
    
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
}
