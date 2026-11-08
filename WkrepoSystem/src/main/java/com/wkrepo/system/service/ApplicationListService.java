package com.wkrepo.system.service;

import com.wkrepo.system.dto.ApplicationDetailDTO;
import com.wkrepo.system.dto.ApplicationItem;
import com.wkrepo.system.dto.MonthlySummaryDTO;
import com.wkrepo.system.entity.GeneralExpense;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.PaidLeave;
import com.wkrepo.system.entity.TravelExpense;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.GeneralExpenseRepository;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.PaidLeaveRepository;
import com.wkrepo.system.repository.TravelExpenseRepository;
import com.wkrepo.system.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.wkrepo.system.dto.ApplicationDetailDTO.AttendanceDailyItem;
import com.wkrepo.system.entity.Attendance;

@Service
public class ApplicationListService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationListService.class);

    @Autowired
    private TravelExpenseRepository travelExpenseRepository;

    @Autowired
    private GeneralExpenseRepository generalExpenseRepository;

    @Autowired
    private PaidLeaveRepository paidLeaveRepository;

    @Autowired
    private MonthlyReportRepository monthlyReportRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MonthlySummaryService monthlySummaryService;

    @Autowired
    private AttendanceService attendanceService;

    @Autowired
    private HolidayService holidayService;

    @Autowired
    private Agreement36Service agreement36Service;

    @Autowired
    private MessageSource messageSource;

    public List<ApplicationItem> getApplicationsForUser(User currentUser) {
        return getApplicationsForUser(currentUser, null, null);
    }

    public List<ApplicationItem> getPendingApprovalsForUser(User currentUser) {
        return getApplicationsForUser(currentUser).stream()
                .filter(ApplicationItem::isCanApprove)
                .collect(Collectors.toList());
    }

    /**
     * 本人の申請のうち、現在「拒否」になっている件数（ホーム画面の通知用）。
     * 年休・経費は削除、月報は再提出すると件数から消える。
     */
    public long countOwnRejected(User user) {
        if (user == null || user.getId() == null) {
            return 0;
        }
        Long userId = user.getId();
        return travelExpenseRepository.countByUserIdAndStatus(userId, ApplicationStatus.REJECTED)
                + generalExpenseRepository.countByUserIdAndStatus(userId, ApplicationStatus.REJECTED)
                + paidLeaveRepository.countByUserIdAndStatus(userId, ApplicationStatus.REJECTED)
                + monthlyReportRepository.countByUserIdAndStatus(userId, ApplicationStatus.REJECTED);
    }

    public List<ApplicationItem> getApplicationsForUser(User currentUser, String filterType, String filterStatus) {
        log.info("[ApplicationListService] getApplicationsForUser 開始 userId={}, name={}, role={}, filterType={}, filterStatus={}",
                currentUser != null ? currentUser.getId() : null,
                currentUser != null ? currentUser.getName() : null,
                currentUser != null ? currentUser.getRole() : null,
                filterType, filterStatus);
        List<ApplicationItem> items = new ArrayList<>();

        boolean isAdmin = "ADMIN".equals(currentUser.getRole());
        boolean isManager = "MANAGER".equals(currentUser.getRole());
        boolean isSoumu = "SOUMU".equals(currentUser.getRole());
        boolean canViewAll = isAdmin || isSoumu;
        final Long currentUserId = currentUser.getId();
        log.debug("[ApplicationListService] isAdmin={}, isManager={}, isSoumu={}", isAdmin, isManager, isSoumu);

        boolean includeTravel = (filterType == null || filterType.isEmpty() || "TRAVEL".equals(filterType));
        boolean includeGeneral = (filterType == null || filterType.isEmpty() || "GENERAL".equals(filterType));
        boolean includePaidLeave = (filterType == null || filterType.isEmpty() || "PAID_LEAVE".equals(filterType));
        boolean includeMonthly = (filterType == null || filterType.isEmpty() || "MONTHLY_REPORT".equals(filterType));

        // ========== 1. 交通費 ==========
        if (includeTravel) try {
            List<TravelExpense> travelExpenses;
            if (canViewAll) {
                travelExpenses = travelExpenseRepository.findAllWithUser();
            } else if (isManager) {
                // 自分の分 + 自部署の分（DB で絞り込み）
                travelExpenses = travelExpenseRepository.findByUserIdOrDepartment(
                        currentUserId, currentUser.getDepartment());
            } else {
                travelExpenses = travelExpenseRepository.findByUserId(currentUserId);
            }
            log.info("[ApplicationListService] 交通費取得完了 count={}", travelExpenses.size());
            for (TravelExpense te : travelExpenses) {
                items.add(convertToApplicationItem(te, currentUser));
            }
        } catch (Exception e) {
            log.error("[ApplicationListService] 交通費取得でエラー", e);
        }

        // ========== 2. 諸経費 ==========
        if (includeGeneral) try {
            List<GeneralExpense> generalExpenses;
            if (canViewAll) {
                generalExpenses = generalExpenseRepository.findAllWithUser();
            } else if (isManager) {
                generalExpenses = generalExpenseRepository.findByUserIdOrDepartment(
                        currentUserId, currentUser.getDepartment());
            } else {
                generalExpenses = generalExpenseRepository.findByUserId(currentUserId);
            }
            log.info("[ApplicationListService] 諸経費取得完了 count={}", generalExpenses.size());
            for (GeneralExpense ge : generalExpenses) {
                items.add(convertToApplicationItem(ge, currentUser));
            }
        } catch (Exception e) {
            log.error("[ApplicationListService] 諸経費取得でエラー", e);
        }

        // ========== 3. 年休 ==========
        if (includePaidLeave) try {
            List<PaidLeave> paidLeaves;
            if (canViewAll) {
                paidLeaves = paidLeaveRepository.findAllWithUser();
            } else if (isManager) {
                paidLeaves = paidLeaveRepository.findByUserIdOrDepartment(
                        currentUserId, currentUser.getDepartment());
            } else {
                paidLeaves = paidLeaveRepository.findByUserId(currentUserId);
            }
            log.info("[ApplicationListService] 年休取得完了 count={}", paidLeaves.size());
            for (PaidLeave pl : paidLeaves) {
                items.add(convertToApplicationItem(pl, currentUser));
            }
        } catch (Exception e) {
            log.error("[ApplicationListService] 年休取得でエラー", e);
        }

        // ========== 4. 月報 ==========
        if (includeMonthly) try {
            List<MonthlyReport> monthlyReports;
            if (canViewAll) {
                monthlyReports = monthlyReportRepository.findAllWithUser();
            } else if (isManager) {
                // 自分の分、自部署の分、および自分が承認者に指定された承認待ち（SUBMITTED）のもの
                monthlyReports = monthlyReportRepository.findForManager(currentUserId, currentUser.getDepartment());
            } else {
                monthlyReports = monthlyReportRepository.findByUserId(currentUserId);
            }
            log.info("[ApplicationListService] 月報取得完了 count={}", monthlyReports.size());
            for (MonthlyReport mr : monthlyReports) {
                items.add(convertToApplicationItem(mr, currentUser));
            }
        } catch (Exception e) {
            log.error("[ApplicationListService] 月報取得でエラー", e);
        }

        // ステータスフィルタ
        if (filterStatus != null && !filterStatus.isEmpty()) {
            items = items.stream()
                    .filter(it -> it.getStatus() != null && filterStatus.equals(it.getStatus().name()))
                    .collect(Collectors.toList());
        }

        // 申請日の降順で並べ替え
        try {
            items.sort(Comparator.comparing(ApplicationItem::getApplicationDate).reversed());
        } catch (Exception e) {
            log.error("[ApplicationListService] ソートでエラー", e);
        }
        log.info("[ApplicationListService] getApplicationsForUser 完了 合計count={}", items.size());
        return items;
    }

    /** 画面の言語で文言を取得する（申請一覧の表示用ラベル） */
    private String label(String key, Object... args) {
        return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }

    private static final DecimalFormat YEN_FORMAT;
    static {
        DecimalFormatSymbols syms = new DecimalFormatSymbols(Locale.JAPAN);
        syms.setGroupingSeparator(',');
        YEN_FORMAT = new DecimalFormat("#,###", syms);
        YEN_FORMAT.setRoundingMode(RoundingMode.DOWN);
    }

    private String formatYen(BigDecimal amount) {
        if (amount == null) return "¥0";
        return "¥" + YEN_FORMAT.format(amount.setScale(0, RoundingMode.DOWN));
    }

    private boolean isPendingOrSubmitted(ApplicationStatus status) {
        return ApplicationStatus.PENDING.equals(status) || ApplicationStatus.SUBMITTED.equals(status);
    }

    public boolean canApproveApplication(Long id, String applicationType, User currentUser) {
        if (id == null || applicationType == null || currentUser == null) {
            return false;
        }

        switch (applicationType) {
            case "TRAVEL":
                return travelExpenseRepository.findById(id)
                        .map(application -> canApprove(currentUser, application.getUser(),
                                application.getStatus() != null ? application.getStatus() : ApplicationStatus.PENDING,
                                null, applicationType))
                        .orElse(false);
            case "GENERAL":
                return generalExpenseRepository.findById(id)
                        .map(application -> canApprove(currentUser, application.getUser(),
                                application.getStatus() != null ? application.getStatus() : ApplicationStatus.PENDING,
                                null, applicationType))
                        .orElse(false);
            case "PAID_LEAVE":
                return paidLeaveRepository.findById(id)
                        .map(application -> canApprove(currentUser, application.getUser(),
                                application.getStatus() != null ? application.getStatus() : ApplicationStatus.PENDING,
                                null, applicationType))
                        .orElse(false);
            case "MONTHLY_REPORT":
                return monthlyReportRepository.findById(id)
                        .map(application -> canApprove(currentUser, application.getUser(),
                                application.getStatus() != null ? application.getStatus() : ApplicationStatus.DRAFT,
                                application.getApproverId(), applicationType))
                        .orElse(false);
            default:
                return false;
        }
    }

    // ===== 交通費の変換 =====
    private ApplicationItem convertToApplicationItem(TravelExpense te, User currentUser) {
        ApplicationItem item = new ApplicationItem();
        item.setId(te.getId());
        item.setApplicantName(te.getUser().getName());
        item.setApplicantDepartment(te.getUser().getDepartment());
        item.setApplicationType("TRAVEL");
        item.setApplicationTypeLabel(label("application.type.TRAVEL"));
        item.setApplicationDate(te.getCreatedAt() != null ? te.getCreatedAt() : LocalDateTime.now());
        item.setAmount(te.getAmount());
        item.setAmountLabel(formatYen(te.getAmount()));
        item.setStatus(te.getStatus() != null ? te.getStatus() : ApplicationStatus.PENDING);
        item.setCanApprove(canApprove(currentUser, te.getUser(), item.getStatus(), null, "TRAVEL"));
        return item;
    }

    // ===== 諸経費の変換 =====
    private ApplicationItem convertToApplicationItem(GeneralExpense ge, User currentUser) {
        ApplicationItem item = new ApplicationItem();
        item.setId(ge.getId());
        item.setApplicantName(ge.getUser().getName());
        item.setApplicantDepartment(ge.getUser().getDepartment());
        item.setApplicationType("GENERAL");
        item.setApplicationTypeLabel(label("application.type.GENERAL"));
        item.setApplicationDate(ge.getCreatedAt() != null ? ge.getCreatedAt() : LocalDateTime.now());
        item.setAmount(ge.getAmount());
        item.setAmountLabel(formatYen(ge.getAmount()));
        item.setStatus(ge.getStatus() != null ? ge.getStatus() : ApplicationStatus.PENDING);
        item.setCanApprove(canApprove(currentUser, ge.getUser(), item.getStatus(), null, "GENERAL"));
        return item;
    }

    // ===== 年休の変換 =====
    private ApplicationItem convertToApplicationItem(PaidLeave pl, User currentUser) {
        ApplicationItem item = new ApplicationItem();
        item.setId(pl.getId());
        item.setApplicantName(pl.getUser().getName());
        item.setApplicantDepartment(pl.getUser().getDepartment());
        item.setApplicationType("PAID_LEAVE");
        item.setApplicationTypeLabel(label("application.type.PAID_LEAVE"));
        item.setApplicationDate(pl.getCreatedAt() != null ? pl.getCreatedAt() : LocalDateTime.now());
        BigDecimal days = pl.getLeaveDays() != null ? pl.getLeaveDays() : BigDecimal.ZERO;
        item.setAmount(days);
        item.setAmountLabel(label("application.amount.days", days.toPlainString()));
        item.setStatus(pl.getStatus() != null ? pl.getStatus() : ApplicationStatus.PENDING);
        item.setCanApprove(canApprove(currentUser, pl.getUser(), item.getStatus(), null, "PAID_LEAVE"));
        return item;
    }

    // ===== 月報の変換 =====
    private ApplicationItem convertToApplicationItem(MonthlyReport mr, User currentUser) {
        ApplicationItem item = new ApplicationItem();
        item.setId(mr.getId());
        item.setApplicantName(mr.getUser() != null ? mr.getUser().getName() : "");
        item.setApplicantDepartment(mr.getUser() != null ? mr.getUser().getDepartment() : "");
        item.setApplicationType("MONTHLY_REPORT");
        item.setApplicationTypeLabel(label("application.type.MONTHLY_REPORT"));
        // 申請日：月報の updatedAt または createdAt を申請日とする
        LocalDateTime applyDate = mr.getUpdatedAt() != null ? mr.getUpdatedAt() : mr.getCreatedAt();
        if (applyDate == null) applyDate = LocalDateTime.now();
        item.setApplicationDate(applyDate);
        // 月報は金額がないため 0 を設定
        item.setAmount(BigDecimal.ZERO);
        item.setAmountLabel(label("application.amount.month", String.valueOf(mr.getYear()), String.valueOf(mr.getMonth())));
        // ステータス：月報の DRAFT も PENDING として表示
        ApplicationStatus status = mr.getStatus() != null ? mr.getStatus() : ApplicationStatus.DRAFT;
        item.setStatus(status);
        // 月報の承認権限判定：approverId を渡す必要がある
        item.setCanApprove(canApprove(currentUser, mr.getUser(), status, mr.getApproverId(), "MONTHLY_REPORT"));
        item.setCanWithdraw(currentUser != null && currentUser.getId() != null
                && mr.getUser() != null && currentUser.getId().equals(mr.getUser().getId())
                && status == ApplicationStatus.SUBMITTED);
        return item;
    }

    /**
     * 承認できるかどうか
     */
    private boolean canApprove(User currentUser, User applicant, ApplicationStatus status, Long approverId, String applicationType) {
        if (applicant == null || currentUser == null || status == null) return false;
        if (currentUser.getId().equals(applicant.getId())) {
            return false;
        }

        boolean isAdmin = "ADMIN".equals(currentUser.getRole());
        boolean isManager = "MANAGER".equals(currentUser.getRole());
        boolean isSoumu = "SOUMU".equals(currentUser.getRole());

        if ("MONTHLY_REPORT".equals(applicationType)) {
            if (ApplicationStatus.SUBMITTED.equals(status)) {
                if (isAdmin) {
                    return true;
                }
                if (isManager) {
                    if (approverId != null && approverId.equals(currentUser.getId())) {
                        return true;
                    }
                    return currentUser.getDepartment() != null &&
                            currentUser.getDepartment().equals(applicant.getDepartment());
                }
                return false;
            }
            if (ApplicationStatus.MANAGER_APPROVED.equals(status)) {
                return isSoumu || isAdmin;
            }
            return false;
        }

        if (!isPendingOrSubmitted(status)) {
            return false;
        }
        if (isAdmin) {
            return true;
        }
        if (isManager) {
            return currentUser.getDepartment() != null &&
                    currentUser.getDepartment().equals(applicant.getDepartment());
        }
        return false;
    }

    private boolean canView(User currentUser, User applicant) {
        if (applicant == null || currentUser == null) return false;
        if (currentUser.getId().equals(applicant.getId())) return true;
        boolean isAdmin = "ADMIN".equals(currentUser.getRole());
        boolean isSoumu = "SOUMU".equals(currentUser.getRole());
        boolean isManager = "MANAGER".equals(currentUser.getRole());
        if (isAdmin || isSoumu) return true;
        if (isManager) {
            return currentUser.getDepartment() != null &&
                    currentUser.getDepartment().equals(applicant.getDepartment());
        }
        return false;
    }

    public ApplicationDetailDTO getApplicationDetail(Long id, String type, User currentUser) {
        log.info("[ApplicationListService] getApplicationDetail 開始 id={}, type={}, userId={}",
                id, type, currentUser != null ? currentUser.getId() : null);
        if (id == null || type == null) {
            throw new IllegalArgumentException("application.error.invalidRequest");
        }

        ApplicationDetailDTO dto = new ApplicationDetailDTO();
        dto.setId(id);
        dto.setApplicationType(type);

        switch (type) {
            case "TRAVEL":
                fillTravelDetail(dto, id, currentUser);
                break;
            case "GENERAL":
                fillGeneralDetail(dto, id, currentUser);
                break;
            case "PAID_LEAVE":
                fillPaidLeaveDetail(dto, id, currentUser);
                break;
            case "MONTHLY_REPORT":
                fillMonthlyReportDetail(dto, id, currentUser);
                break;
            default:
                throw new IllegalArgumentException("application.error.invalidRequest");
        }

        log.info("[ApplicationListService] getApplicationDetail 完了 id={}, type={}", id, type);
        return dto;
    }

    private void fillTravelDetail(ApplicationDetailDTO dto, Long id, User currentUser) {
        Optional<TravelExpense> opt = travelExpenseRepository.findById(id);
        if (!opt.isPresent()) {
            throw new RuntimeException("TravelExpense not found: id=" + id);
        }
        TravelExpense te = opt.get();
        User applicant = te.getUser();
        if (!canView(currentUser, applicant)) {
            throw new SecurityException("Permission denied: cannot view this application");
        }

        dto.setApplicationTypeLabel(label("application.type.TRAVEL"));
        dto.setApplicantName(applicant != null ? applicant.getName() : "");
        dto.setApplicantDepartment(applicant != null ? applicant.getDepartment() : "");
        ApplicationStatus status = te.getStatus() != null ? te.getStatus() : ApplicationStatus.PENDING;
        dto.setStatus(status.name());
        dto.setStatusDisplayName(status.getDisplayName());
        dto.setApplicationDate(te.getCreatedAt() != null ? te.getCreatedAt() : LocalDateTime.now());
        dto.setCanApprove(canApprove(currentUser, applicant, status, null, "TRAVEL"));
        dto.setApproveButtonLabel("承認");

        dto.setExpenseDate(te.getExpenseDate());
        dto.setTravelType(te.getType());
        dto.setTransportation(te.getTransportation());
        dto.setRoute(te.getRoute());
        dto.setTravelSummary(te.getSummary());
        dto.setTravelAmount(te.getAmount() != null ? te.getAmount() : BigDecimal.ZERO);
    }

    private void fillGeneralDetail(ApplicationDetailDTO dto, Long id, User currentUser) {
        Optional<GeneralExpense> opt = generalExpenseRepository.findById(id);
        if (!opt.isPresent()) {
            throw new RuntimeException("GeneralExpense not found: id=" + id);
        }
        GeneralExpense ge = opt.get();
        User applicant = ge.getUser();
        if (!canView(currentUser, applicant)) {
            throw new SecurityException("Permission denied: cannot view this application");
        }

        dto.setApplicationTypeLabel(label("application.type.GENERAL"));
        dto.setApplicantName(applicant != null ? applicant.getName() : "");
        dto.setApplicantDepartment(applicant != null ? applicant.getDepartment() : "");
        ApplicationStatus status = ge.getStatus() != null ? ge.getStatus() : ApplicationStatus.PENDING;
        dto.setStatus(status.name());
        dto.setStatusDisplayName(status.getDisplayName());
        dto.setApplicationDate(ge.getCreatedAt() != null ? ge.getCreatedAt() : LocalDateTime.now());
        dto.setCanApprove(canApprove(currentUser, applicant, status, null, "GENERAL"));
        dto.setApproveButtonLabel("承認");

        dto.setGeneralDate(ge.getDate());
        dto.setExpenseType(ge.getExpenseType());
        dto.setGeneralSummary(ge.getSummary());
        dto.setGeneralAmount(ge.getAmount() != null ? ge.getAmount() : BigDecimal.ZERO);
        dto.setGeneralRemarks(ge.getRemarks());
    }

    private void fillPaidLeaveDetail(ApplicationDetailDTO dto, Long id, User currentUser) {
        Optional<PaidLeave> opt = paidLeaveRepository.findById(id);
        if (!opt.isPresent()) {
            throw new RuntimeException("PaidLeave not found: id=" + id);
        }
        PaidLeave pl = opt.get();
        User applicant = pl.getUser();
        if (!canView(currentUser, applicant)) {
            throw new SecurityException("Permission denied: cannot view this application");
        }

        dto.setApplicationTypeLabel(label("application.type.PAID_LEAVE"));
        dto.setApplicantName(applicant != null ? applicant.getName() : "");
        dto.setApplicantDepartment(applicant != null ? applicant.getDepartment() : "");
        ApplicationStatus status = pl.getStatus() != null ? pl.getStatus() : ApplicationStatus.PENDING;
        dto.setStatus(status.name());
        dto.setStatusDisplayName(status.getDisplayName());
        dto.setApplicationDate(pl.getCreatedAt() != null ? pl.getCreatedAt() : LocalDateTime.now());
        dto.setCanApprove(canApprove(currentUser, applicant, status, null, "PAID_LEAVE"));
        dto.setApproveButtonLabel("承認");

        dto.setStartDate(pl.getStartDate());
        dto.setEndDate(pl.getEndDate());
        dto.setLeaveDays(pl.getLeaveDays() != null ? pl.getLeaveDays() : BigDecimal.ZERO);
        dto.setLeaveType(pl.getLeaveType());
        dto.setLeaveRemarks(pl.getRemarks());
    }

    private void fillMonthlyReportDetail(ApplicationDetailDTO dto, Long id, User currentUser) {
        Optional<MonthlyReport> opt = monthlyReportRepository.findById(id);
        if (!opt.isPresent()) {
            throw new RuntimeException("MonthlyReport not found: id=" + id);
        }
        MonthlyReport mr = opt.get();
        User applicant = mr.getUser();
        if (!canView(currentUser, applicant)) {
            throw new SecurityException("Permission denied: cannot view this application");
        }

        dto.setApplicationTypeLabel(label("application.type.MONTHLY_REPORT"));
        dto.setApplicantName(applicant != null ? applicant.getName() : "");
        dto.setApplicantDepartment(applicant != null ? applicant.getDepartment() : "");
        ApplicationStatus status = mr.getStatus() != null ? mr.getStatus() : ApplicationStatus.DRAFT;
        dto.setStatus(status.name());
        dto.setStatusDisplayName(status.getDisplayName());
        LocalDateTime applyDate = mr.getUpdatedAt() != null ? mr.getUpdatedAt() : mr.getCreatedAt();
        if (applyDate == null) applyDate = LocalDateTime.now();
        dto.setApplicationDate(applyDate);
        dto.setCanApprove(canApprove(currentUser, applicant, status, mr.getApproverId(), "MONTHLY_REPORT"));
        dto.setCanWithdraw(currentUser != null && currentUser.getId() != null
                && applicant != null && currentUser.getId().equals(applicant.getId())
                && status == ApplicationStatus.SUBMITTED);

        if (ApplicationStatus.MANAGER_APPROVED.equals(status)
                && ("SOUMU".equals(currentUser.getRole()) || "ADMIN".equals(currentUser.getRole()))) {
            dto.setApproveButtonLabel("最終承認");
        } else {
            dto.setApproveButtonLabel("承認");
        }

        dto.setReportYear(mr.getYear());
        dto.setReportMonth(mr.getMonth());
        dto.setWorkContent(mr.getWorkContent());
        dto.setApproverId(mr.getApproverId());
        if (mr.getApproverId() != null) {
            Optional<User> approverOpt = userRepository.findById(mr.getApproverId());
            approverOpt.ifPresent(u -> dto.setApproverName(u.getName()));
        }

        if (applicant != null) {
            try {
                MonthlySummaryDTO summary = monthlySummaryService.calculateMonthlySummary(
                        applicant, mr.getYear(), mr.getMonth());
                dto.setMonthlySummary(summary);
            } catch (Exception e) {
                log.warn("[ApplicationListService] MonthlySummary計算でエラー reportId={}", id, e);
            }
            try {
                Agreement36Service.Result agreement36 =
                        agreement36Service.check(applicant, YearMonth.of(mr.getYear(), mr.getMonth()));
                Locale locale = LocaleContextHolder.getLocale();
                dto.setAgreement36Alerts(agreement36.alerts().stream()
                        .map(alert -> messageSource.getMessage(alert.messageKey(), alert.args(), locale))
                        .collect(Collectors.toList()));
                dto.setAgreement36HasErrors(agreement36.hasErrors());
            } catch (Exception e) {
                log.warn("[ApplicationListService] 36協定チェックでエラー reportId={}", id, e);
            }
            try {
                dto.setAttendanceList(buildAttendanceDailyList(applicant, mr.getYear(), mr.getMonth()));
            } catch (Exception e) {
                log.warn("[ApplicationListService] 勤怠一覧計算でエラー reportId={}", id, e);
            }
        }
    }

    private List<AttendanceDailyItem> buildAttendanceDailyList(User user, int year, int month) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("MM-dd");
        Locale locale = LocaleContextHolder.getLocale();

        List<Attendance> records = attendanceService.getAttendancesByUserAndDateRange(user, start, end);
        Map<LocalDate, Attendance> recordMap = new HashMap<>();
        for (Attendance a : records) {
            if (a != null && a.getDate() != null) {
                recordMap.put(a.getDate(), a);
            }
        }

        List<AttendanceDailyItem> list = new ArrayList<>();
        for (int day = 1; day <= ym.lengthOfMonth(); day++) {
            LocalDate d = ym.atDay(day);
            AttendanceDailyItem item = new AttendanceDailyItem();
            item.setDate(d.format(dateFmt));
            item.setDateLabel(d.format(labelFmt));
            DayOfWeek dow = d.getDayOfWeek();
            item.setDayOfWeek(dow.getDisplayName(java.time.format.TextStyle.SHORT, locale));
            item.setSaturday(dow == DayOfWeek.SATURDAY);
            item.setSunday(dow == DayOfWeek.SUNDAY);
            try {
                item.setHoliday(holidayService.isRestDay(d) && !item.isSaturday() && !item.isSunday());
            } catch (Exception e) {
                item.setHoliday(false);
            }

            Attendance a = recordMap.get(d);
            if (a != null) {
                String status = a.getAgreement36Status();
                boolean noInput = (status == null || status.trim().isEmpty() || "-".equals(status.trim()));
                item.setStartTime(!noInput && a.getStartTime() != null ? a.getStartTime().toString() : "-");
                item.setEndTime(!noInput && a.getEndTime() != null ? a.getEndTime().toString() : "-");
                item.setBreakTime(!noInput && a.getBreakTime() != null ? a.getBreakTime().toString() : "-");
                item.setWorkHours(a.getWorkHours() != null ? a.getWorkHours() : BigDecimal.ZERO);
                item.setOvertimeHours(a.getOvertimeHours() != null ? a.getOvertimeHours() : BigDecimal.ZERO);
                item.setStatus((status != null && !status.trim().isEmpty()) ? status : "-");
                item.setRemarks(a.getRemarks());
            } else {
                item.setStartTime("-");
                item.setEndTime("-");
                item.setBreakTime("-");
                item.setWorkHours(BigDecimal.ZERO);
                item.setOvertimeHours(BigDecimal.ZERO);
                item.setStatus("-");
                item.setRemarks(null);
            }
            list.add(item);
        }
        return list;
    }
}
