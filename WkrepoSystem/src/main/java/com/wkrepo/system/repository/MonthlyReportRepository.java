package com.wkrepo.system.repository;

import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long> {

    /** ホーム画面の「拒否された申請」件数用 */
    long countByUserIdAndStatus(Long userId, ApplicationStatus status);

    Optional<MonthlyReport> findByUserAndYearAndMonth(User user, int year, int month);

    Optional<MonthlyReport> findByUserIdAndYearAndMonth(Long userId, int year, int month);

    List<MonthlyReport> findByUserOrderByYearDescMonthDesc(User user);

    List<MonthlyReport> findByUserId(Long userId);

    List<MonthlyReport> findByApproverId(Long approverId);

    List<MonthlyReport> findByApproverIdAndStatus(Long approverId, ApplicationStatus status);

    // 申請一覧用：申請者もまとめて取得し、1 件ずつの読み込み（N+1）を避ける
    @Query("SELECT m FROM MonthlyReport m JOIN FETCH m.user")
    List<MonthlyReport> findAllWithUser();

    /**
     * 部長用：自分の月報、自部署の月報、および自分（または代理中の上司）が承認者に指定された承認待ち（SUBMITTED）の月報。
     * approverIds は空にしない（本人の ID を必ず含める）
     */
    @Query("SELECT m FROM MonthlyReport m JOIN FETCH m.user u WHERE u.id = :userId OR u.department = :department"
            + " OR (m.approverId IN :approverIds AND m.status = com.wkrepo.system.enums.ApplicationStatus.SUBMITTED)")
    List<MonthlyReport> findForManager(@Param("userId") Long userId, @Param("department") String department,
                                       @Param("approverIds") java.util.Collection<Long> approverIds);
}
