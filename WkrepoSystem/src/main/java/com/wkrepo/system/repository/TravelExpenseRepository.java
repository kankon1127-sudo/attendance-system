package com.wkrepo.system.repository;

import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TravelExpenseRepository extends JpaRepository<TravelExpense, Long> {
    
    List<TravelExpense> findByUserAndExpenseDateBetweenOrderByExpenseDateAsc(User user, LocalDate startDate, LocalDate endDate);
    
    List<TravelExpense> findByExpenseDateBetweenOrderByExpenseDateAsc(LocalDate startDate, LocalDate endDate);
    
    List<TravelExpense> findByUserOrderByExpenseDateDesc(User user);
    
    List<TravelExpense> findByUserId(Long userId);
}
