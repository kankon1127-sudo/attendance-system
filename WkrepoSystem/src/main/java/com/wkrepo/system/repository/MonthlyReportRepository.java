package com.wkrepo.system.repository;

import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long> {

    Optional<MonthlyReport> findByUserAndYearAndMonth(User user, int year, int month);

    Optional<MonthlyReport> findByUserIdAndYearAndMonth(Long userId, int year, int month);

    List<MonthlyReport> findByUserOrderByYearDescMonthDesc(User user);

    List<MonthlyReport> findByUserId(Long userId);

    List<MonthlyReport> findByApproverId(Long approverId);

    List<MonthlyReport> findByApproverIdAndStatus(Long approverId, ApplicationStatus status);
}
