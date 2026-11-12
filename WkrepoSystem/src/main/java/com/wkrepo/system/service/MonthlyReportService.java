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

    @Autowired
    private ApprovalHistoryService approvalHistoryService;

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
            if (report.getStatus() != ApplicationStatus.DRAFT
                    && report.getStatus() != ApplicationStatus.REJECTED) {
                throw new IllegalStateException("monthly.error.notEditable");
            }
            if (workContent != null) {
                report.setWorkContent(workContent);
            }
            report.setStatus(ApplicationStatus.DRAFT);
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
            MonthlyReport report = findOrCreateReport(user, year, month);
            log.info("[MonthlyReportService] 変更前 status={}", report.getStatus());
            if (report.getStatus() != ApplicationStatus.DRAFT
                    && report.getStatus() != ApplicationStatus.REJECTED) {
                throw new IllegalStateException("monthly.error.notResubmittable");
            }
            attendanceService.validatePaidLeaveApprovedForMonthlySubmit(user, year, month);
            if (workContent != null) {
                report.setWorkContent(workContent);
            }
            if (approverId != null) {
                report.setApproverId(approverId);
            }
            MonthlyReport saved = monthlyReportRepository.save(report);
            approvalHistoryService.transition("MONTHLY_REPORT", saved.getId(), ApplicationStatus.SUBMITTED, user);
            saved.setStatus(ApplicationStatus.SUBMITTED);
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
    public MonthlyReport withdrawReport(Long id, User user) {
        if (user == null || user.getId() == null) {
            throw new SecurityException("common.error.loginRequired");
        }
        MonthlyReport report = monthlyReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("monthly.error.notFound"));
        if (report.getUser() == null || !user.getId().equals(report.getUser().getId())) {
            throw new SecurityException("monthly.error.withdrawNotOwner");
        }
        if (report.getStatus() != ApplicationStatus.SUBMITTED) {
            throw new IllegalStateException("monthly.error.withdrawNotSubmitted");
        }

        approvalHistoryService.transition("MONTHLY_REPORT", id, ApplicationStatus.DRAFT, user);
        return monthlyReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("monthly.error.notFound"));
    }

    /**
     * 確定済みの月報を差し戻す（総務・管理者のみ、理由は必須）。状態は「拒否」に戻り、当月のロックが外れるので、
     * 本人が勤怠・経費を直して再提出できる。理由は承認履歴に残る。
     */
    @Transactional
    public ApprovalHistoryService.Transition reopenReport(Long id, User actor, String reason) {
        MonthlyReport report = monthlyReportRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("monthly.error.notFound"));
        if (!ApplicationListService.canReopen(actor, report)) {
            throw new SecurityException("monthly.error.reopenNotAllowed");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("monthly.error.reopenReasonRequired");
        }
        log.info("[MonthlyReportService] 確定済み月報の差し戻し reportId={}, actorId={}", id, actor.getId());
        return approvalHistoryService.transition("MONTHLY_REPORT", id, ApplicationStatus.REJECTED, actor, reason);
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
