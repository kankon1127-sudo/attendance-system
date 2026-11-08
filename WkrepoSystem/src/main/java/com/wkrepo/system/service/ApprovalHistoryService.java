package com.wkrepo.system.service;

import com.wkrepo.system.dto.ApplicationItem;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ApprovalHistoryService {

    /** コメント（拒否理由）の最大文字数。approval_history.comment の列長と同じ */
    public static final int MAX_COMMENT_LENGTH = 500;

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

    /**
     * 状態遷移の結果。申請者への通知に使う。
     *
     * @param applicant 申請者
     * @param summary   申請内容の要約（通知メール用、日本語。例：「年休 2026-11-13〜2026-11-16（2.0 日）」）
     */
    public record Transition(User applicant, String summary) {
    }

    @Transactional
    public Transition transition(String applicationType, Long applicationId, ApplicationStatus nextStatus, User actor) {
        return transition(applicationType, applicationId, nextStatus, actor, null);
    }

    /**
     * 申請の状態を変更し、承認履歴を記録する。
     *
     * @param comment 承認者のコメント（拒否理由など。任意、前後の空白は除く、500 文字まで）
     */
    @Transactional
    public Transition transition(String applicationType, Long applicationId, ApplicationStatus nextStatus, User actor,
                                 String comment) {
        if (applicationType == null || applicationId == null || nextStatus == null || actor == null
                || actor.getId() == null) {
            throw new IllegalArgumentException("Application, status and actor are required");
        }
        String normalizedComment = normalizeComment(comment);

        ApplicationStatus previousStatus;
        Transition result;
        switch (applicationType) {
            case "TRAVEL" -> {
                var application = travelExpenseRepository.findById(applicationId)
                        .orElseThrow(() -> new IllegalArgumentException("application.error.notFound"));
                previousStatus = application.getStatus() == null ? ApplicationStatus.PENDING : application.getStatus();
                application.setStatus(nextStatus);
                travelExpenseRepository.save(application);
                result = new Transition(application.getUser(),
                        "交通費 " + application.getExpenseDate() + " " + yen(application.getAmount()));
            }
            case "GENERAL" -> {
                var application = generalExpenseRepository.findById(applicationId)
                        .orElseThrow(() -> new IllegalArgumentException("application.error.notFound"));
                previousStatus = application.getStatus() == null ? ApplicationStatus.PENDING : application.getStatus();
                application.setStatus(nextStatus);
                generalExpenseRepository.save(application);
                result = new Transition(application.getUser(),
                        "諸経費 " + application.getDate() + " " + yen(application.getAmount()));
            }
            case "PAID_LEAVE" -> {
                var application = paidLeaveRepository.findById(applicationId)
                        .orElseThrow(() -> new IllegalArgumentException("application.error.notFound"));
                previousStatus = application.getStatus() == null ? ApplicationStatus.PENDING : application.getStatus();
                application.setStatus(nextStatus);
                paidLeaveRepository.save(application);
                result = new Transition(application.getUser(),
                        "年休 " + application.getStartDate() + "〜" + application.getEndDate()
                                + "（" + (application.getLeaveDays() == null ? "-" : application.getLeaveDays().toPlainString())
                                + " 日）");
            }
            case "MONTHLY_REPORT" -> {
                var application = monthlyReportRepository.findById(applicationId)
                        .orElseThrow(() -> new IllegalArgumentException("application.error.notFound"));
                previousStatus = application.getStatus() == null ? ApplicationStatus.DRAFT : application.getStatus();
                application.setStatus(nextStatus);
                monthlyReportRepository.save(application);
                result = new Transition(application.getUser(),
                        "月報 " + application.getYear() + "年" + application.getMonth() + "月");
            }
            default -> throw new IllegalArgumentException("application.error.invalidRequest");
        }

        ApprovalHistory history = new ApprovalHistory();
        history.setApplicationType(applicationType);
        history.setApplicationId(applicationId);
        history.setPreviousStatus(previousStatus.name());
        history.setNextStatus(nextStatus.name());
        history.setActorId(actor.getId());
        history.setActorName(actor.getName() == null ? actor.getUsername() : actor.getName());
        history.setActionAt(LocalDateTime.now());
        history.setComment(normalizedComment);
        historyRepository.save(history);
        return result;
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
                        history.getActionAt(),
                        history.getComment()))
                .toList();
    }

    /**
     * 申請一覧の表示用：拒否された申請に、最新の拒否理由を設定する。
     * 1 ページ分の申請について、種類ごとに 1 回だけ問い合わせる。
     */
    @Transactional(readOnly = true)
    public void fillRejectReasons(List<ApplicationItem> items) {
        Map<String, List<ApplicationItem>> rejectedByType = items.stream()
                .filter(item -> item.getStatus() == ApplicationStatus.REJECTED && item.getId() != null)
                .collect(Collectors.groupingBy(ApplicationItem::getApplicationType));
        rejectedByType.forEach((type, rejected) -> {
            Map<Long, String> latestReason = new HashMap<>();
            historyRepository.findByApplicationTypeAndApplicationIdInAndNextStatusOrderByActionAtAscIdAsc(
                            type, rejected.stream().map(ApplicationItem::getId).toList(),
                            ApplicationStatus.REJECTED.name())
                    // 古い順なので、後の履歴で上書きすれば最新の拒否のコメントが残る（理由なしの再拒否も含む）
                    .forEach(history -> latestReason.put(history.getApplicationId(), history.getComment()));
            rejected.forEach(item -> item.setRejectReason(latestReason.get(item.getId())));
        });
    }

    static String normalizeComment(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        String trimmed = comment.strip();
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("application.error.commentTooLong");
        }
        return trimmed;
    }

    private static String yen(BigDecimal amount) {
        if (amount == null) {
            return "-";
        }
        return NumberFormat.getNumberInstance(Locale.JAPAN).format(amount.setScale(0, RoundingMode.DOWN)) + " 円";
    }
}
