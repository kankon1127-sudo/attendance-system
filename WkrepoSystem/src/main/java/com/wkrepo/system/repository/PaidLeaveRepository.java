package com.wkrepo.system.repository;

import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PaidLeaveRepository extends JpaRepository<PaidLeave, Long> {
    
    List<PaidLeave> findByUserAndStartDateBetweenOrderByStartDateAsc(User user, LocalDate startDate, LocalDate endDate);
    
    List<PaidLeave> findByUserOrderByStartDateDesc(User user);

    List<PaidLeave> findByUserAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            User user, ApplicationStatus status, LocalDate startDate, LocalDate endDate);

    List<PaidLeave> findByUserAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            User user, LocalDate startDate, LocalDate endDate);

    List<PaidLeave> findByUserAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            User user, List<ApplicationStatus> statuses, LocalDate endDate, LocalDate startDate);
    
    @Query("SELECT COALESCE(SUM(pl.leaveDays), 0) FROM PaidLeave pl WHERE pl.user.id = :userId AND pl.startDate >= :startDate AND pl.endDate <= :endDate")
    Double getTotalLeaveDaysByUserAndDateRange(@Param("userId") Long userId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
    
    List<PaidLeave> findByUserId(Long userId);
}
