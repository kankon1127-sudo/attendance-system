package com.wkrepo.system.service;

import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.GeneralExpenseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class GeneralExpenseService {
    
    @Autowired
    private GeneralExpenseRepository generalExpenseRepository;

    @Autowired
    private AttendanceService attendanceService;
    
    public GeneralExpense saveGeneralExpense(GeneralExpense expense) {
        validateEditableExpense(expense);
        if (expense.getStatus() == null) {
            expense.setStatus(ApplicationStatus.PENDING);
        }
        return generalExpenseRepository.save(expense);
    }
    
    public void deleteGeneralExpense(Long id, User user) {
        GeneralExpense existing = generalExpenseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("諸経費データが見つかりません。"));
        ensureOwner(existing.getUser(), user);
        attendanceService.ensureMonthEditable(existing.getUser(), existing.getDate());
        generalExpenseRepository.delete(existing);
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

    private void validateEditableExpense(GeneralExpense expense) {
        if (expense.getId() != null) {
            GeneralExpense existing = generalExpenseRepository.findById(expense.getId())
                    .orElseThrow(() -> new IllegalArgumentException("諸経費データが見つかりません。"));
            ensureOwner(existing.getUser(), expense.getUser());
            attendanceService.ensureMonthEditable(existing.getUser(), existing.getDate());
        }
        attendanceService.ensureMonthEditable(expense.getUser(), expense.getDate());
    }

    private void ensureOwner(User owner, User currentUser) {
        if (owner == null || owner.getId() == null || currentUser == null || currentUser.getId() == null
                || !Objects.equals(owner.getId(), currentUser.getId())) {
            throw new SecurityException("自分の諸経費データのみ変更できます。");
        }
    }
}
