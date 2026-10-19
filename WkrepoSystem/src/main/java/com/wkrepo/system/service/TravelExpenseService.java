package com.wkrepo.system.service;

import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.TravelExpenseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
public class TravelExpenseService {
    
    @Autowired
    private TravelExpenseRepository travelExpenseRepository;
    
    public List<TravelExpense> getTravelExpensesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return travelExpenseRepository.findByUserAndExpenseDateBetweenOrderByExpenseDateAsc(user, startDate, endDate);
    }
    
    public List<TravelExpense> getAllTravelExpensesByUser(User user) {
        return travelExpenseRepository.findByUserOrderByExpenseDateDesc(user);
    }
    
    public TravelExpense saveTravelExpense(TravelExpense travelExpense) {
        if (travelExpense.getStatus() == null) {
            travelExpense.setStatus(ApplicationStatus.PENDING);
        }
        return travelExpenseRepository.save(travelExpense);
    }
    
    public TravelExpense updateTravelExpense(TravelExpense travelExpense) {
        return travelExpenseRepository.save(travelExpense);
    }
    
    public void deleteTravelExpense(Long id) {
        travelExpenseRepository.deleteById(id);
    }
    
    public TravelExpense findById(Long id) {
        return travelExpenseRepository.findById(id).orElse(null);
    }
    
    public void updateStatus(Long id, ApplicationStatus status) {
        TravelExpense te = findById(id);
        if (te != null) {
            te.setStatus(status);
            travelExpenseRepository.save(te);
        }
    }
}