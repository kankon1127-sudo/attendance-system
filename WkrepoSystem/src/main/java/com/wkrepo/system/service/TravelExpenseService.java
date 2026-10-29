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
import java.util.Objects;

@Service
@Transactional
public class TravelExpenseService {
    
    @Autowired
    private TravelExpenseRepository travelExpenseRepository;

    @Autowired
    private AttendanceService attendanceService;
    
    public List<TravelExpense> getTravelExpensesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return travelExpenseRepository.findByUserAndExpenseDateBetweenOrderByExpenseDateAsc(user, startDate, endDate);
    }
    
    public List<TravelExpense> getAllTravelExpensesByUser(User user) {
        return travelExpenseRepository.findByUserOrderByExpenseDateDesc(user);
    }
    
    public TravelExpense saveTravelExpense(TravelExpense travelExpense) {
        validateEditableExpense(travelExpense);
        if (travelExpense.getStatus() == null) {
            travelExpense.setStatus(ApplicationStatus.PENDING);
        }
        return travelExpenseRepository.save(travelExpense);
    }
    
    public TravelExpense updateTravelExpense(TravelExpense travelExpense) {
        validateEditableExpense(travelExpense);
        return travelExpenseRepository.save(travelExpense);
    }
    
    public void deleteTravelExpense(Long id, User user) {
        TravelExpense existing = travelExpenseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("交通費データが見つかりません。"));
        ensureOwner(existing.getUser(), user);
        attendanceService.ensureMonthEditable(existing.getUser(), existing.getExpenseDate());
        travelExpenseRepository.delete(existing);
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

    private void validateEditableExpense(TravelExpense expense) {
        if (expense.getId() != null) {
            TravelExpense existing = travelExpenseRepository.findById(expense.getId())
                    .orElseThrow(() -> new IllegalArgumentException("交通費データが見つかりません。"));
            ensureOwner(existing.getUser(), expense.getUser());
            attendanceService.ensureMonthEditable(existing.getUser(), existing.getExpenseDate());
        }
        attendanceService.ensureMonthEditable(expense.getUser(), expense.getExpenseDate());
    }

    private void ensureOwner(User owner, User currentUser) {
        if (owner == null || owner.getId() == null || currentUser == null || currentUser.getId() == null
                || !Objects.equals(owner.getId(), currentUser.getId())) {
            throw new SecurityException("自分の交通費データのみ変更できます。");
        }
    }
}