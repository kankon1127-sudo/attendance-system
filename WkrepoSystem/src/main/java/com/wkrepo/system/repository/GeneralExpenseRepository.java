package com.wkrepo.system.repository;

import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface GeneralExpenseRepository extends JpaRepository<GeneralExpense, Long> {
    
    List<GeneralExpense> findByUserAndDateBetweenOrderByDateAsc(User user, LocalDate startDate, LocalDate endDate);
    
    List<GeneralExpense> findByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);
    
    List<GeneralExpense> findByUserOrderByDateDesc(User user);
    
    List<GeneralExpense> findByUserId(Long userId);

    // 申請一覧用：申請者もまとめて取得し、1 件ずつの読み込み（N+1）を避ける
    @Query("SELECT g FROM GeneralExpense g JOIN FETCH g.user")
    List<GeneralExpense> findAllWithUser();

    @Query("SELECT g FROM GeneralExpense g JOIN FETCH g.user u WHERE u.id = :userId OR u.department = :department")
    List<GeneralExpense> findByUserIdOrDepartment(@Param("userId") Long userId, @Param("department") String department);
}
