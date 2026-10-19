package com.wkrepo.system.repository;

import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface GeneralExpenseRepository extends JpaRepository<GeneralExpense, Long> {
    
    List<GeneralExpense> findByUserAndDateBetweenOrderByDateAsc(User user, LocalDate startDate, LocalDate endDate);
    
    List<GeneralExpense> findByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);
    
    List<GeneralExpense> findByUserOrderByDateDesc(User user);
    
    List<GeneralExpense> findByUserId(Long userId);
}
