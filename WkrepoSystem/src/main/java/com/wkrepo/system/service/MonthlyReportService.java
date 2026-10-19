package com.wkrepo.system.service;

import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class MonthlyReportService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportService.class);

    @Autowired
    private MonthlyReportRepository monthlyReportRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AttendanceService attendanceService;

    public MonthlyReport findOrCreateReport(User user, int year, int month) {
        Long userId = user.getId();
        log.info("[MonthlyReportService] findOrCreateReport userId={}, year={}, month={}", userId, year, month);
        try {
            Optional<MonthlyReport> existing = monthlyReportRepository.findByUserIdAndYearAndMonth(userId, year, month);
            if (existing.isPresent()) {
                MonthlyReport r = existing.get();
                log.info("[MonthlyReportService] 既存レポートあり reportId={}, status={}", r.getId(), r.getStatus());
                return r;
            }
            log.info("[MonthlyReportService] 新規レポート作成 userId={}", userId);
            MonthlyReport report = new MonthlyReport();
            User managedUser = userRepository.findById(userId).orElse(user);
            report.setUser(managedUser);
            report.setYear(year);
            report.setMonth(month);
            report.setStatus(ApplicationStatus.DRAFT);
            MonthlyReport saved = monthlyReportRepository.save(report);
            log.info("[MonthlyReportService] 新規レポート保存完了 reportId={}, status={}", saved.getId(), saved.getStatus());
            return saved;
        } catch (Exception e) {
            log.error("[MonthlyReportService] findOrCreateReportでエラー userId={}, year={}, month={}",
                    userId, year, month, e);
            throw e;
        }
    }

    public Optional<MonthlyReport> findReport(User user, int year, int month) {
        Long userId = user.getId();
        log.debug("[MonthlyReportService] findReport userId={}, year={}, month={}", userId, year, month);
        try {
            Optional<MonthlyReport> result = monthlyReportRepository.findByUserIdAndYearAndMonth(userId, year, month);
            log.debug("[MonthlyReportService] findReport結果 found={}", result.isPresent());
            return result;
        } catch (Exception e) {
            log.error("[MonthlyReportService] findReportでエラー userId={}, year={}, month={}",
                    userId, year, month, e);
            throw e;
        }
    }

    @Transactional
    public MonthlyReport saveDraft(User user, int year, int month, String workContent) {
        log.info("[MonthlyReportService] saveDraft開始 userId={}, year={}, month={}", user.getId(), year, month);
        try {
            MonthlyReport report = findOrCreateReport(user, year, month);
            if (workContent != null) {
                report.setWorkContent(workContent);
            }
            MonthlyReport saved = monthlyReportRepository.save(report);
            log.info("[MonthlyReportService] saveDraft完了 reportId={}, status={}", saved.getId(), saved.getStatus());
            return saved;
        } catch (Exception e) {
            log.error("[MonthlyReportService] saveDraft失敗 userId={}", user.getId(), e);
            throw e;
        }
    }

    @Transactional
    public MonthlyReport submitReport(User user, int year, int month, String workContent, Long approverId) {
        log.info("[MonthlyReportService] submitReport開始 userId={}, year={}, month={}, approverId={}",
                user.getId(), year, month, approverId);
        try {
            attendanceService.validatePaidLeaveApprovedForMonthlySubmit(user, year, month);
            MonthlyReport report = findOrCreateReport(user, year, month);
            log.info("[MonthlyReportService] 変更前 status={}", report.getStatus());
            if (workContent != null) {
                report.setWorkContent(workContent);
            }
            if (approverId != null) {
                report.setApproverId(approverId);
            }
            report.setStatus(ApplicationStatus.SUBMITTED);
            MonthlyReport saved = monthlyReportRepository.save(report);
            log.info("[MonthlyReportService] submitReport完了 reportId={}, status={}", saved.getId(), saved.getStatus());
            return saved;
        } catch (Exception e) {
            log.error("[MonthlyReportService] submitReport失敗 userId={}", user.getId(), e);
            throw e;
        }
    }

    public int statusToStep(ApplicationStatus status) {
        if (status == null) {
            return 1;
        }
        return switch (status) {
            case DRAFT -> 1;
            case SUBMITTED, PENDING -> 2;
            case MANAGER_APPROVED, APPROVED -> 3;
            case FINALIZED -> 4;
            default -> 1;
        };
    }

    public ApplicationStatus stepToStatus(int step) {
        return switch (step) {
            case 1 -> ApplicationStatus.DRAFT;
            case 2 -> ApplicationStatus.SUBMITTED;
            case 3 -> ApplicationStatus.MANAGER_APPROVED;
            case 4 -> ApplicationStatus.FINALIZED;
            default -> ApplicationStatus.DRAFT;
        };
    }

    public MonthlyReport findById(Long id) {
        return monthlyReportRepository.findById(id).orElse(null);
    }

    @Transactional
    public void updateStatus(Long id, ApplicationStatus status) {
        log.info("[MonthlyReportService] updateStatus reportId={}, newStatus={}", id, status);
        try {
            MonthlyReport report = findById(id);
            if (report != null) {
                report.setStatus(status);
                monthlyReportRepository.save(report);
                log.info("[MonthlyReportService] updateStatus完了 reportId={}, status={}", report.getId(), report.getStatus());
            }
        } catch (Exception e) {
            log.error("[MonthlyReportService] updateStatus失敗 reportId={}", id, e);
            throw e;
        }
    }
}
