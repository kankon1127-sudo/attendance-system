package com.wkrepo.system.service;

import com.wkrepo.system.dto.ApprovalHistoryEntry;
import com.wkrepo.system.entity.ApprovalHistory;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.ApprovalHistoryRepository;
import com.wkrepo.system.repository.GeneralExpenseRepository;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.TravelExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.LocalDateTime;

@Service
public class ApprovalHistoryService {

    private final ApprovalHistoryRepository historyRepository;
    private final TravelExpenseRepository travelExpenseRepository;
    private final GeneralExpenseRepository generalExpenseRepository;
    private final PaidLeaveRepository paidLeaveRepository;
    private final MonthlyReportRepository monthlyReportRepository;

    public ApprovalHistoryService(
            ApprovalHistoryRepository historyRepository,
            TravelExpenseRepository travelExpenseRepository,
            GeneralExpenseRepository generalExpenseRepository,
            PaidLeaveRepository paidLeaveRepository,
            MonthlyReportRepository monthlyReportRepository) {
        this.historyRepository = historyRepository;
        this.travelExpenseRepository = travelExpenseRepository;
        this.generalExpenseRepository = generalExpenseRepository;
        this.paidLeaveRepository = paidLeaveRepository;
        this.monthlyReportRepository = monthlyReportRepository;
    }

    @Transactional
    public void transition(String applicationType, Long applicationId, ApplicationStatus nextStatus, User actor) {
        if (applicationType == null || applicationId == null || nextStatus == null || actor == null
                || actor.getId() == null) {
            throw new IllegalArgumentException("Application, status and actor are required");
        }

        ApplicationStatus previousStatus = switch (applicationType) {
            case "TRAVEL" -> travelExpenseRepository.findById(applicationId)
                    .map(application -> {
                        ApplicationStatus status = application.getStatus() == null
                                ? ApplicationStatus.PENDING : application.getStatus();
                        application.setStatus(nextStatus);
                        travelExpenseRepository.save(application);
                        return status;
                    }).orElseThrow(() -> new IllegalArgumentException("Travel expense not found"));
            case "GENERAL" -> generalExpenseRepository.findById(applicationId)
                    .map(application -> {
                        ApplicationStatus status = application.getStatus() == null
                                ? ApplicationStatus.PENDING : application.getStatus();
                        application.setStatus(nextStatus);
                        generalExpenseRepository.save(application);
                        return status;
                    }).orElseThrow(() -> new IllegalArgumentException("General expense not found"));
            case "PAID_LEAVE" -> paidLeaveRepository.findById(applicationId)
                    .map(application -> {
                        ApplicationStatus status = application.getStatus() == null
                                ? ApplicationStatus.PENDING : application.getStatus();
                        application.setStatus(nextStatus);
                        paidLeaveRepository.save(application);
                        return status;
                    }).orElseThrow(() -> new IllegalArgumentException("Paid leave not found"));
            case "MONTHLY_REPORT" -> monthlyReportRepository.findById(applicationId)
                    .map(application -> {
                        ApplicationStatus status = application.getStatus() == null
                                ? ApplicationStatus.DRAFT : application.getStatus();
                        application.setStatus(nextStatus);
                        monthlyReportRepository.save(application);
                        return status;
                    }).orElseThrow(() -> new IllegalArgumentException("Monthly report not found"));
            default -> throw new IllegalArgumentException("Unknown application type: " + applicationType);
        };

        ApprovalHistory history = new ApprovalHistory();
        history.setApplicationType(applicationType);
        history.setApplicationId(applicationId);
        history.setPreviousStatus(previousStatus.name());
        history.setNextStatus(nextStatus.name());
        history.setActorId(actor.getId());
        history.setActorName(actor.getName() == null ? actor.getUsername() : actor.getName());
        history.setActionAt(LocalDateTime.now());
        historyRepository.save(history);
    }

    @Transactional(readOnly = true)
    public List<ApprovalHistoryEntry> getHistory(String applicationType, Long applicationId) {
        return historyRepository
                .findByApplicationTypeAndApplicationIdOrderByActionAtAscIdAsc(applicationType, applicationId)
                .stream()
                .map(history -> new ApprovalHistoryEntry(
                        history.getPreviousStatus(),
                        history.getNextStatus(),
                        history.getActorName(),
                        history.getActionAt()))
                .toList();
    }
}
