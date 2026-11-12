package com.wkrepo.system.service;

import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;

/**
 * 定期リマインド（ReminderScheduler から毎朝呼ぶ。締め状況画面からの手動送信にも使う）。
 * <ol>
 *   <li>前月の月報の未提出：毎月 {@code app.reminder.monthly-report-day} 日以降、本人に週 1 回。総務にも未提出人数を通知</li>
 *   <li>承認の滞留：7 日以上待っている申請がある承認者（代理・総務を含む）に週 1 回</li>
 *   <li>36 協定：当月の時間外が警告ライン以上なら本人に月 1 回、36 協定の警告が出たら本人・直属の上司・総務に（件数が増えるたび）</li>
 * </ol>
 * 同じ内容は dedupe キーで 1 回だけ作る（NotificationService）。
 */
@Service
public class ReminderService {

    private static final Logger logger = LoggerFactory.getLogger(ReminderService.class);

    /** 月報の提出が必要か：前月の月報が未作成・下書き・拒否 */
    private static final List<ApplicationStatus> NOT_SUBMITTED = List.of(ApplicationStatus.DRAFT, ApplicationStatus.REJECTED);

    private final UserRepository userRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final ApplicationListService applicationListService;
    private final Agreement36Service agreement36Service;
    private final NotificationService notificationService;
    private final int monthlyReportDay;
    private final BigDecimal overtimeWarningHours;
    private final Clock clock;

    @Autowired
    public ReminderService(UserRepository userRepository, MonthlyReportRepository monthlyReportRepository,
                           ApplicationListService applicationListService, Agreement36Service agreement36Service,
                           NotificationService notificationService,
                           @Value("${app.reminder.monthly-report-day:3}") int monthlyReportDay,
                           @Value("${app.reminder.overtime-warning-hours:40}") BigDecimal overtimeWarningHours) {
        this(userRepository, monthlyReportRepository, applicationListService, agreement36Service, notificationService,
                monthlyReportDay, overtimeWarningHours, Clock.systemDefaultZone());
    }

    ReminderService(UserRepository userRepository, MonthlyReportRepository monthlyReportRepository,
                    ApplicationListService applicationListService, Agreement36Service agreement36Service,
                    NotificationService notificationService, int monthlyReportDay, BigDecimal overtimeWarningHours,
                    Clock clock) {
        this.userRepository = userRepository;
        this.monthlyReportRepository = monthlyReportRepository;
        this.applicationListService = applicationListService;
        this.agreement36Service = agreement36Service;
        this.notificationService = notificationService;
        this.monthlyReportDay = monthlyReportDay;
        this.overtimeWarningHours = overtimeWarningHours;
        this.clock = clock;
    }

    /** 実行結果（作成した通知の件数） */
    public record Summary(int monthlyReport, int approval, int agreement36) {
        public int total() {
            return monthlyReport + approval + agreement36;
        }
    }

    /** 毎朝のリマインドをすべて実行する。1 種類が失敗しても他は続ける */
    public Summary runAll() {
        LocalDate today = LocalDate.now(clock);
        int monthly = safely("月報未提出", () -> remindUnsubmittedMonthlyReports(today));
        int approval = safely("承認滞留", () -> remindOverdueApprovals(today));
        int agreement36 = safely("36協定", () -> remindAgreement36(today));
        Summary summary = new Summary(monthly, approval, agreement36);
        logger.info("定期リマインドを実行しました：月報 {} 件、承認滞留 {} 件、36協定 {} 件",
                summary.monthlyReport(), summary.approval(), summary.agreement36());
        return summary;
    }

    /** 前月の月報の未提出（月初の指定日以降、週 1 回） */
    int remindUnsubmittedMonthlyReports(LocalDate today) {
        if (today.getDayOfMonth() < monthlyReportDay) {
            return 0;
        }
        int week = (today.getDayOfMonth() - monthlyReportDay) / 7;
        return remindUnsubmitted(YearMonth.from(today).minusMonths(1), "w" + week);
    }

    /** 締め状況画面から手動で送る（同じ日に 2 回押しても 1 回だけ） */
    public int remindUnsubmittedNow(YearMonth month) {
        return remindUnsubmitted(month, "manual-" + LocalDate.now(clock));
    }

    private int remindUnsubmitted(YearMonth month, String round) {
        List<User> unsubmitted = unsubmittedUsers(month);
        int created = 0;
        String link = "/monthly-summary?year=" + month.getYear() + "&month=" + month.getMonthValue();
        for (User user : unsubmitted) {
            if (notificationService.notify(user, "MONTHLY_UNSUBMITTED", "notification.monthlyUnsubmitted",
                    List.of(String.valueOf(month.getYear()), String.valueOf(month.getMonthValue())), link,
                    "MONTHLY_UNSUBMITTED:" + month + ":" + round)) {
                created++;
            }
        }
        if (!unsubmitted.isEmpty()) {
            String closingLink = "/closing?year=" + month.getYear() + "&month=" + month.getMonthValue();
            for (User soumu : soumuRecipients()) {
                if (notificationService.notify(soumu, "MONTHLY_UNSUBMITTED", "notification.monthlyUnsubmittedSummary",
                        List.of(String.valueOf(month.getYear()), String.valueOf(month.getMonthValue()),
                                String.valueOf(unsubmitted.size())),
                        closingLink, "MONTHLY_UNSUBMITTED_SUMMARY:" + month + ":" + round)) {
                    created++;
                }
            }
        }
        return created;
    }

    /** 月報の提出が必要な人（有効で直属の上司が設定されている人）のうち、指定月をまだ提出していない人 */
    public List<User> unsubmittedUsers(YearMonth month) {
        List<User> result = new ArrayList<>();
        for (User user : userRepository.findAll()) {
            if (!mustSubmitMonthlyReport(user)) {
                continue;
            }
            ApplicationStatus status = monthlyReportRepository
                    .findByUserIdAndYearAndMonth(user.getId(), month.getYear(), month.getMonthValue())
                    .map(MonthlyReport::getStatus)
                    .orElse(null);
            if (status == null || NOT_SUBMITTED.contains(status)) {
                result.add(user);
            }
        }
        return result;
    }

    /** 月報を提出する人：有効で、直属の上司（承認者）が設定されている */
    public static boolean mustSubmitMonthlyReport(User user) {
        return user != null && !"INACTIVE".equals(user.getStatus()) && user.getManagerId() != null;
    }

    /** 7 日以上承認待ちの申請がある承認者に、週 1 回まとめて通知する */
    int remindOverdueApprovals(LocalDate today) {
        String week = today.get(IsoFields.WEEK_BASED_YEAR) + "W" + today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int created = 0;
        for (User approver : userRepository.findAll()) {
            if ("INACTIVE".equals(approver.getStatus()) || "GENERAL".equals(approver.getRole())) {
                continue;
            }
            long overdue = applicationListService.getPendingApprovalsForUser(approver).stream()
                    .filter(ApplicationItem::isOverdue)
                    .count();
            if (overdue > 0 && notificationService.notify(approver, "APPROVAL_OVERDUE", "notification.approvalOverdue",
                    List.of(String.valueOf(overdue)), "/application/list", "APPROVAL_OVERDUE:" + week)) {
                created++;
            }
        }
        return created;
    }

    /** 36 協定：当月の時間外が警告ライン以上なら本人に月 1 回、警告が出たら本人・上司・総務に（件数が増えるたび） */
    int remindAgreement36(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        List<User> soumu = soumuRecipients();
        int created = 0;
        for (User user : userRepository.findAll()) {
            if ("INACTIVE".equals(user.getStatus())) {
                continue;
            }
            Agreement36Service.Result result = agreement36Service.check(user, month);
            String hours = result.monthlyOvertime().setScale(1, RoundingMode.HALF_UP).toPlainString();
            String attendanceLink = "/attendance?year=" + month.getYear() + "&month=" + month.getMonthValue();
            if (result.monthlyOvertime().compareTo(overtimeWarningHours) >= 0
                    && notificationService.notify(user, "AGREEMENT36", "notification.overtimeNear",
                    List.of(hours, overtimeWarningHours.stripTrailingZeros().toPlainString()), attendanceLink,
                    "AGREEMENT36_NEAR:" + month)) {
                created++;
            }
            if (!result.hasAlerts()) {
                continue;
            }
            int alertCount = result.alerts().size();
            String dedupe = "AGREEMENT36:" + month + ":" + user.getId() + ":" + alertCount;
            if (notificationService.notify(user, "AGREEMENT36", "notification.agreement36Self",
                    List.of(String.valueOf(alertCount), hours), attendanceLink, dedupe)) {
                created++;
            }
            // 上司と総務へ（本人を除き、同じ人には 1 回だけ。User は equals を持たないので ID で重複を除く）
            java.util.Map<Long, User> others = new java.util.LinkedHashMap<>();
            if (user.getManagerId() != null) {
                userRepository.findById(user.getManagerId()).ifPresent(manager -> others.put(manager.getId(), manager));
            }
            soumu.forEach(s -> others.putIfAbsent(s.getId(), s));
            others.remove(user.getId());
            String teamLink = "/team/attendance?userId=" + user.getId() + "&year=" + month.getYear()
                    + "&month=" + month.getMonthValue();
            for (User other : others.values()) {
                boolean canOpenTeamView = TeamService.canUseTeamView(other);
                if (notificationService.notify(other, "AGREEMENT36", "notification.agreement36Member",
                        List.of(user.getName(), String.valueOf(alertCount), hours),
                        canOpenTeamView ? teamLink : "/closing", dedupe)) {
                    created++;
                }
            }
        }
        return created;
    }

    /** 総務への通知先：有効な総務。総務がいなければ管理者 */
    List<User> soumuRecipients() {
        List<User> all = userRepository.findAll();
        List<User> soumu = all.stream()
                .filter(user -> "SOUMU".equals(user.getRole()) && !"INACTIVE".equals(user.getStatus()))
                .toList();
        if (!soumu.isEmpty()) {
            return soumu;
        }
        return all.stream()
                .filter(user -> "ADMIN".equals(user.getRole()) && !"INACTIVE".equals(user.getStatus()))
                .toList();
    }

    private static int safely(String name, java.util.function.IntSupplier task) {
        try {
            return task.getAsInt();
        } catch (RuntimeException e) {
            logger.error("定期リマインド（{}）でエラー", name, e);
            return 0;
        }
    }
}
