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

    @Autowired
    private ExpenseAmountPolicy expenseAmountPolicy;

    /**
     * 新規申請の登録（既存データを編集する機能はない）。状態は常に「申請中」から始める。
     * id や状態がリクエストから渡されても使わない（自分で承認済みにしたり他人のデータを上書きさせない）。
     */
    public GeneralExpense saveGeneralExpense(GeneralExpense expense) {
        if (expense.getId() != null) {
            throw new IllegalArgumentException("application.error.invalidRequest");
        }
        expenseAmountPolicy.validate(expense.getAmount());
        attendanceService.ensureMonthEditable(expense.getUser(), expense.getDate());
        expense.setStatus(ApplicationStatus.PENDING);
        return generalExpenseRepository.save(expense);
    }

    /** 本人の、申請中または拒否された申請だけ削除できる（承認済みは変更不可） */
    public void deleteGeneralExpense(Long id, User user) {
        GeneralExpense existing = generalExpenseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("expense.error.generalNotFound"));
        ensureOwner(existing.getUser(), user);
        if (!TravelExpenseService.isChangeable(existing.getStatus())) {
            throw new IllegalStateException("expense.error.approvedNotEditable");
        }
        attendanceService.ensureMonthEditable(existing.getUser(), existing.getDate());
        generalExpenseRepository.delete(existing);
    }
    
    public List<GeneralExpense> getGeneralExpensesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return generalExpenseRepository.findByUserAndDateBetweenOrderByDateAsc(user, startDate, endDate);
    }

    public List<GeneralExpense> getAllGeneralExpensesByDateRange(LocalDate startDate, LocalDate endDate) {
        return generalExpenseRepository.findByDateBetweenOrderByDateAsc(startDate, endDate);
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

    private void ensureOwner(User owner, User currentUser) {
        if (owner == null || owner.getId() == null || currentUser == null || currentUser.getId() == null
                || !Objects.equals(owner.getId(), currentUser.getId())) {
            throw new SecurityException("expense.error.generalNotOwner");
        }
    }
}
