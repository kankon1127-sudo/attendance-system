package com.wkrepo.system.service;

import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.GeneralExpenseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class GeneralExpenseService {
    
    @Autowired
    private GeneralExpenseRepository generalExpenseRepository;
    
    public GeneralExpense saveGeneralExpense(GeneralExpense expense) {
        if (expense.getStatus() == null) {
            expense.setStatus(ApplicationStatus.PENDING);
        }
        return generalExpenseRepository.save(expense);
    }
    
    public void deleteGeneralExpense(Long id) {
        generalExpenseRepository.deleteById(id);
    }
    
    public List<GeneralExpense> getGeneralExpensesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return generalExpenseRepository.findByUserAndDateBetweenOrderByDateAsc(user, startDate, endDate);
    }
    
    public GeneralExpense getGeneralExpenseById(Long id) {
        return generalExpenseRepository.findById(id).orElse(null);
    }
    
    public void updateStatus(Long id, ApplicationStatus status) {
        GeneralExpense ge = getGeneralExpenseById(id);
        if (ge != null) {
            ge.setStatus(status);
            generalExpenseRepository.save(ge);
        }
    }
}
