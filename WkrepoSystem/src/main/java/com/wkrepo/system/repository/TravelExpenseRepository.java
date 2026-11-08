package com.wkrepo.system.repository;

import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TravelExpenseRepository extends JpaRepository<TravelExpense, Long> {

    /** ホーム画面の「拒否された申請」件数用 */
    long countByUserIdAndStatus(Long userId, com.wkrepo.system.enums.ApplicationStatus status);
    
    List<TravelExpense> findByUserAndExpenseDateBetweenOrderByExpenseDateAsc(User user, LocalDate startDate, LocalDate endDate);
    
    List<TravelExpense> findByExpenseDateBetweenOrderByExpenseDateAsc(LocalDate startDate, LocalDate endDate);
    
    List<TravelExpense> findByUserOrderByExpenseDateDesc(User user);
    
    List<TravelExpense> findByUserId(Long userId);

    // 申請一覧用：申請者もまとめて取得し、1 件ずつの読み込み（N+1）を避ける
    @Query("SELECT t FROM TravelExpense t JOIN FETCH t.user")
    List<TravelExpense> findAllWithUser();

    @Query("SELECT t FROM TravelExpense t JOIN FETCH t.user u WHERE u.id = :userId OR u.department = :department")
    List<TravelExpense> findByUserIdOrDepartment(@Param("userId") Long userId, @Param("department") String department);
}
