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
    
    public List<TravelExpense> getAllTravelExpensesByDateRange(LocalDate startDate, LocalDate endDate) {
        return travelExpenseRepository.findByExpenseDateBetweenOrderByExpenseDateAsc(startDate, endDate);
    }

    public List<TravelExpense> getAllTravelExpensesByUser(User user) {
        return travelExpenseRepository.findByUserOrderByExpenseDateDesc(user);
    }
    
    /**
     * 新規申請の登録（既存データを編集する機能はない）。状態は常に「申請中」から始める。
     * id や状態がリクエストから渡されても使わない（自分で承認済みにしたり他人のデータを上書きさせない）。
     */
    public TravelExpense saveTravelExpense(TravelExpense travelExpense) {
        if (travelExpense.getId() != null) {
            throw new IllegalArgumentException("application.error.invalidRequest");
        }
        attendanceService.ensureMonthEditable(travelExpense.getUser(), travelExpense.getExpenseDate());
        travelExpense.setStatus(ApplicationStatus.PENDING);
        return travelExpenseRepository.save(travelExpense);
    }

    /** 本人の、申請中または拒否された申請だけ削除できる（承認済みは変更不可） */
    public void deleteTravelExpense(Long id, User user) {
        TravelExpense existing = travelExpenseRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("expense.error.travelNotFound"));
        ensureOwner(existing.getUser(), user);
        if (!isChangeable(existing.getStatus())) {
            throw new IllegalStateException("expense.error.approvedNotEditable");
        }
        attendanceService.ensureMonthEditable(existing.getUser(), existing.getExpenseDate());
        travelExpenseRepository.delete(existing);
    }

    /** 申請者が変更・削除できる状態か（申請中・拒否）。交通費・諸経費で共通 */
    public static boolean isChangeable(ApplicationStatus status) {
        return status == null || status == ApplicationStatus.PENDING || status == ApplicationStatus.REJECTED;
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

    private void ensureOwner(User owner, User currentUser) {
        if (owner == null || owner.getId() == null || currentUser == null || currentUser.getId() == null
                || !Objects.equals(owner.getId(), currentUser.getId())) {
            throw new SecurityException("expense.error.travelNotOwner");
        }
    }
}