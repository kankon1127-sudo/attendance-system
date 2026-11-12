package com.wkrepo.system.service;

import com.wkrepo.system.dto.AttendanceAnomaly;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.AttendanceRepository;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.enums.ApplicationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
public class AttendanceService {
    
    @Autowired
    private AttendanceRepository attendanceRepository;

    @Autowired
    private MonthlyReportRepository monthlyReportRepository;
    
    @Autowired
    private HolidayService holidayService;
    
    @Autowired
    private PaidLeaveService paidLeaveService;

    /** 代休：休日出勤日から何日以内に取るか */
    @org.springframework.beans.factory.annotation.Value("${app.attendance.compensatory-leave-days:60}")
    private int compensatoryValidityDays = 60;

    /** 振休：休日出勤日の前後何日以内に振り替えるか */
    @org.springframework.beans.factory.annotation.Value("${app.attendance.substitute-holiday-days:28}")
    private int substituteWindowDays = 28;
    
    private static final BigDecimal STANDARD_WORK_HOURS = new BigDecimal("8.0");
    private static final BigDecimal OVERTIME_THRESHOLD = new BigDecimal("8.0");
    
    public Attendance createAttendance(Attendance attendance) {
        ensureMonthEditable(attendance.getUser(), attendance.getDate());
        validateAttendance(attendance);
        calculateWorkHours(attendance);
        determineAgreement36Status(attendance);
        return attendanceRepository.save(attendance);
    }
    
    public Attendance updateAttendance(Attendance attendance) {
        ensureMonthEditable(attendance.getUser(), attendance.getDate());
        // ID が空でも同じ日付のレコードが既にあれば ID を引き継ぎ、「新規作成」ではなく「更新」にする
        if (attendance.getId() == null && attendance.getUser() != null && attendance.getDate() != null) {
            attendanceRepository.findByUserAndDate(attendance.getUser(), attendance.getDate())
                    .ifPresent(existing -> attendance.setId(existing.getId()));
        }
        
        validateAttendance(attendance);
        calculateWorkHours(attendance);
        determineAgreement36Status(attendance);
        Attendance saved = attendanceRepository.save(attendance);
        recalculateLinkedRestDays(saved);
        return saved;
    }

    /**
     * 振休を設定・変更・解除したとき、対応する休日出勤日を再計算する
     * （振り替えた休日は平日として計算するため、休日出勤日の残業・休日労働が変わる）。
     */
    private void recalculateLinkedRestDays(Attendance saved) {
        java.util.Set<LocalDate> dates = new java.util.LinkedHashSet<>();
        if (saved.getLoadedSubstituteDate() != null) {
            dates.add(saved.getLoadedSubstituteDate());
        }
        if (saved.getSubstituteDate() != null) {
            dates.add(saved.getSubstituteDate());
        }
        for (LocalDate date : dates) {
            attendanceRepository.findByUserAndDate(saved.getUser(), date).ifPresent(restDay -> {
                if (isMonthLocked(restDay.getUser(), date.getYear(), date.getMonthValue())) {
                    return; // 提出済みの月の数値は変えない
                }
                calculateWorkHours(restDay);
                attendanceRepository.save(restDay);
            });
        }
    }

    public static boolean isPaidLeaveStatus(String status) {
        return "有給".equals(status) || "有休".equals(status);
    }

    // ---- 勤務区分 ----
    public static final String STATUS_MORNING_OFF = "午前休";
    public static final String STATUS_AFTERNOON_OFF = "午後休";
    public static final String STATUS_SPECIAL_LEAVE = "特別休暇";
    public static final String STATUS_SUBSTITUTE_HOLIDAY = "振休";
    public static final String STATUS_COMPENSATORY_LEAVE = "代休";

    /** 画面に出す勤務区分（保存値 → 画面の文言キー）。選択肢の順番もこの順 */
    public static final Map<String, String> STATUS_LABEL_KEYS;
    static {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        labels.put("出社", "attendance.status.office");
        labels.put("在宅", "attendance.status.home");
        labels.put(STATUS_MORNING_OFF, "attendance.status.morningOff");
        labels.put(STATUS_AFTERNOON_OFF, "attendance.status.afternoonOff");
        labels.put("有給", "attendance.status.paidLeave");
        labels.put(STATUS_SPECIAL_LEAVE, "attendance.status.specialLeave");
        labels.put(STATUS_SUBSTITUTE_HOLIDAY, "attendance.status.substituteHoliday");
        labels.put(STATUS_COMPENSATORY_LEAVE, "attendance.status.compensatoryLeave");
        labels.put("欠勤", "attendance.status.absent");
        STATUS_LABEL_KEYS = java.util.Collections.unmodifiableMap(labels);
    }

    /** 半休（午前休・午後休）：半日は年休、残りの半日は勤務する */
    public static boolean isHalfDayOffStatus(String status) {
        return STATUS_MORNING_OFF.equals(status) || STATUS_AFTERNOON_OFF.equals(status);
    }

    /** 半休の区分に対応する年休申請の区分（午前休 → 午前、午後休 → 午後） */
    public static String halfDayLeaveType(String status) {
        return STATUS_MORNING_OFF.equals(status) ? "午前" : (STATUS_AFTERNOON_OFF.equals(status) ? "午後" : null);
    }

    /** 振休・代休（対応する休日出勤日が必要） */
    public static boolean isSubstituteStatus(String status) {
        return STATUS_SUBSTITUTE_HOLIDAY.equals(status) || STATUS_COMPENSATORY_LEAVE.equals(status);
    }

    /** 勤務しない区分（時刻を入力しない、実働 0）：-・欠勤・有給・特別休暇・振休・代休 */
    public static boolean isNonWorkingStatus(String status) {
        return "-".equals(status) || "欠勤".equals(status) || isPaidLeaveStatus(status)
                || STATUS_SPECIAL_LEAVE.equals(status) || isSubstituteStatus(status);
    }

    /** 出勤日数に数える区分：出社・在宅・半休 */
    public static boolean isWorkDayStatus(String status) {
        return "出社".equals(status) || "在宅".equals(status) || isHalfDayOffStatus(status);
    }

    public boolean isMonthLocked(User user, int year, int month) {
        if (user == null || user.getId() == null) {
            return false;
        }
        return monthlyReportRepository.findByUserIdAndYearAndMonth(user.getId(), year, month)
                .map(MonthlyReport::getStatus)
                .map(status -> status != ApplicationStatus.DRAFT && status != ApplicationStatus.REJECTED)
                .orElse(false);
    }

    public void ensureMonthEditable(User user, LocalDate date) {
        if (user != null && date != null && isMonthLocked(user, date.getYear(), date.getMonthValue())) {
            throw new IllegalStateException("attendance.error.monthLocked");
        }
    }

    /**
     * 月報提出時：勤怠で有給を選択した日は、年休申請済みかつリーダー承認済みであること
     */
    public void validatePaidLeaveApprovedForMonthlySubmit(User user, int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();
        List<Attendance> attendances = getAttendancesByUserAndDateRange(user, startDate, endDate);

        List<LocalDate> missingApplicationDates = new java.util.ArrayList<>();
        List<LocalDate> unapprovedDates = new java.util.ArrayList<>();

        for (Attendance attendance : attendances) {
            String status = attendance.getAgreement36Status();
            LocalDate date = attendance.getDate();
            // 半休（午前休・午後休）は、同じ日の同じ半日の年休申請が承認済みであること
            if (isHalfDayOffStatus(status)) {
                String halfDayType = halfDayLeaveType(status);
                if (paidLeaveService.isHalfDayLeaveApproved(user, date, halfDayType)) {
                    continue;
                }
                if (paidLeaveService.hasPendingHalfDayLeave(user, date, halfDayType)) {
                    unapprovedDates.add(date);
                } else {
                    missingApplicationDates.add(date);
                }
                continue;
            }
            if (!isPaidLeaveStatus(status)) {
                continue;
            }
            if (paidLeaveService.isPaidLeaveApproved(user, date)) {
                continue;
            }
            if (paidLeaveService.hasPendingPaidLeave(user, date)) {
                unapprovedDates.add(date);
            } else {
                missingApplicationDates.add(date);
            }
        }

        if (missingApplicationDates.isEmpty() && unapprovedDates.isEmpty()) {
            return;
        }

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd");
        throw new BusinessException("monthly.error.paidLeaveNotApproved",
                formatDates(missingApplicationDates, formatter), formatDates(unapprovedDates, formatter));
    }

    private static String formatDates(List<LocalDate> dates, java.time.format.DateTimeFormatter formatter) {
        return dates.isEmpty() ? "-"
                : dates.stream().map(formatter::format).collect(java.util.stream.Collectors.joining(", "));
    }
    
    public void deleteAttendance(Long id) {
        attendanceRepository.deleteById(id);
    }
    
    public Attendance getAttendanceById(Long id) {
        return attendanceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("attendance.error.notFound"));
    }
    
    public Attendance getAttendanceByUserAndDate(User user, LocalDate date) {
        return attendanceRepository.findByUserAndDate(user, date)
                .orElse(null);
    }
    
    public List<Attendance> getAttendancesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return attendanceRepository.findByUserAndDateBetweenOrderByDateAsc(user, startDate, endDate);
    }

    /**
     * 月次勤怠統計の唯一の実装。勤怠画面・ホーム・月報・Excel 出力はすべてここの結果を使う。
     * -/欠勤/有給/空 のレコードはスキップする。出勤日数は実働時間 > 0 の 出社/在宅 のみ数える。
     */
    public MonthlyStatistics calculateMonthlyStatistics(List<Attendance> attendances, YearMonth yearMonth) {
        BigDecimal totalWorkHours = BigDecimal.ZERO;
        BigDecimal totalOvertimeHours = BigDecimal.ZERO;
        BigDecimal totalNightHours = BigDecimal.ZERO;
        BigDecimal totalHolidayHours = BigDecimal.ZERO;
        int totalWorkDays = 0;

        for (Attendance attendance : attendances) {
            if (attendance.getDate() == null || !YearMonth.from(attendance.getDate()).equals(yearMonth)) {
                continue;
            }
            String status = attendance.getAgreement36Status();
            if (isNonWorkingStatus(status) || status == null || status.isBlank()) {
                continue;
            }

            BigDecimal workHours = attendance.getWorkHours() == null
                    ? BigDecimal.ZERO : attendance.getWorkHours();
            BigDecimal overtimeHours = attendance.getOvertimeHours() == null
                    ? BigDecimal.ZERO : attendance.getOvertimeHours();
            totalWorkHours = totalWorkHours.add(workHours);
            totalOvertimeHours = totalOvertimeHours.add(overtimeHours);
            if (attendance.getNightHours() != null) {
                totalNightHours = totalNightHours.add(attendance.getNightHours());
            }
            if (attendance.getHolidayHours() != null) {
                totalHolidayHours = totalHolidayHours.add(attendance.getHolidayHours());
            }

            if (isWorkDayStatus(status) && workHours.compareTo(BigDecimal.ZERO) > 0) {
                totalWorkDays++;
            }
        }
        return new MonthlyStatistics(totalWorkHours, totalOvertimeHours, totalWorkDays,
                totalNightHours, totalHolidayHours);
    }

    public List<MonthlyTrend> calculateMonthlyTrend(
            List<Attendance> attendances, YearMonth firstMonth, int monthCount) {
        Map<YearMonth, List<Attendance>> attendanceByMonth = new HashMap<>();
        for (int i = 0; i < monthCount; i++) {
            attendanceByMonth.put(firstMonth.plusMonths(i), new ArrayList<>());
        }
        for (Attendance attendance : attendances) {
            if (attendance.getDate() == null) {
                continue;
            }
            YearMonth month = YearMonth.from(attendance.getDate());
            List<Attendance> monthAttendances = attendanceByMonth.get(month);
            if (monthAttendances != null) {
                monthAttendances.add(attendance);
            }
        }

        List<MonthlyStatistics> statistics = new ArrayList<>();
        BigDecimal maxHours = BigDecimal.ZERO;
        for (YearMonth month : attendanceByMonth.keySet().stream().sorted().toList()) {
            MonthlyStatistics monthly = calculateMonthlyStatistics(attendanceByMonth.get(month), month);
            statistics.add(monthly);
            maxHours = maxHours.max(monthly.workHours()).max(monthly.overtimeHours());
        }

        BigDecimal scale = maxHours.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ONE : maxHours;
        List<MonthlyTrend> trend = new ArrayList<>();
        for (int i = 0; i < statistics.size(); i++) {
            MonthlyStatistics monthly = statistics.get(i);
            YearMonth month = firstMonth.plusMonths(i);
            trend.add(new MonthlyTrend(
                    month.toString(),
                    monthly.workHours(),
                    monthly.overtimeHours(),
                    monthly.workDays(),
                    barPercent(monthly.workHours(), scale),
                    barPercent(monthly.overtimeHours(), scale)));
        }
        return trend;
    }

    private int barPercent(BigDecimal value, BigDecimal scale) {
        return value.multiply(BigDecimal.valueOf(100))
                .divide(scale, 0, RoundingMode.HALF_UP)
                .intValue();
    }

    public List<AttendanceAnomaly> findMonthlyAnomalies(User user, List<Attendance> attendances) {
        List<AttendanceAnomaly> anomalies = new ArrayList<>();
        for (Attendance attendance : attendances) {
            LocalDate date = attendance.getDate();
            if (date == null) {
                continue;
            }

            String status = attendance.getAgreement36Status();
            boolean hasTime = attendance.getStartTime() != null || attendance.getEndTime() != null;
            boolean hasRecordedHours = attendance.getWorkHours() != null
                    && attendance.getWorkHours().compareTo(BigDecimal.ZERO) > 0;
            if ((status == null || status.isBlank() || "-".equals(status))
                    && (hasTime || hasRecordedHours)) {
                anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.missingStatus"));
                continue;
            }

            if (isHalfDayOffStatus(status)) {
                // 半休：同じ日の同じ半日の年休申請が承認済みであること
                String halfDayType = halfDayLeaveType(status);
                if (!paidLeaveService.isHalfDayLeaveApproved(user, date, halfDayType)) {
                    anomalies.add(new AttendanceAnomaly(date,
                            paidLeaveService.hasPendingHalfDayLeave(user, date, halfDayType)
                                    ? "attendance.anomaly.paidLeavePending" : "attendance.anomaly.halfDayLeaveMissing"));
                }
            }
            if (isSubstituteStatus(status) && attendance.getSubstituteDate() == null) {
                anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.substituteDateMissing"));
            }
            if (STATUS_SPECIAL_LEAVE.equals(status)
                    && (attendance.getRemarks() == null || attendance.getRemarks().isBlank())) {
                anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.specialLeaveNoReason"));
            }

            if (isWorkDayStatus(status)) {
                if (attendance.getStartTime() == null || attendance.getEndTime() == null) {
                    anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.missingTime"));
                } else if (attendance.getBreakTime() != null) {
                    long shiftMinutes = shiftMinutes(attendance);
                    long breakMinutes = attendance.getBreakTime().toSecondOfDay() / 60;
                    if (breakMinutes > shiftMinutes) {
                        anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.breakExceedsShift"));
                    }
                }
            }

            if (isPaidLeaveStatus(status)) {
                if (hasTime || hasRecordedHours) {
                    anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.paidLeaveHasWork"));
                }
                if (!paidLeaveService.isPaidLeaveApproved(user, date)) {
                    String messageKey = paidLeaveService.hasPendingPaidLeave(user, date)
                            ? "attendance.anomaly.paidLeavePending"
                            : "attendance.anomaly.paidLeaveMissing";
                    anomalies.add(new AttendanceAnomaly(date, messageKey));
                }
            } else if ("欠勤".equals(status) && (hasTime || hasRecordedHours)) {
                anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.absenceHasWork"));
            }
        }
        return anomalies;
    }
    
    public YearMonth getFirstAttendanceYearMonth(User user) {
        LocalDate firstDate = attendanceRepository.findFirstDateByUser(user);
        if (firstDate != null) {
            return YearMonth.from(firstDate);
        }
        return null;
    }
    
    public List<Attendance> getAllAttendancesByDateRange(LocalDate startDate, LocalDate endDate) {
        return attendanceRepository.findByDateBetweenOrderByDateAsc(startDate, endDate);
    }
    
    /**
     * 一括デフォルト入力処理
     */
    public void bulkApplyDefaults(User user, int year, int month, DefaultAttendanceSetting setting) {
        if (isMonthLocked(user, year, month)) {
            throw new IllegalStateException("attendance.error.monthLocked");
        }
        if (setting == null) {
            throw new RuntimeException("settings.error.notFound");
        }

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        // 既存レコードを取得
        List<Attendance> existingAttendances = getAttendancesByUserAndDateRange(user, startDate, endDate);
        java.util.Set<LocalDate> existingDates = existingAttendances.stream()
                .filter(a -> a.getAgreement36Status() != null && !"-".equals(a.getAgreement36Status()) && !"".equals(a.getAgreement36Status()))
                .map(Attendance::getDate)
                .collect(java.util.stream.Collectors.toSet());

        for (int i = 1; i <= yearMonth.lengthOfMonth(); i++) {
            LocalDate date = yearMonth.atDay(i);
            
            // ルール1：土曜日と日曜日は処理しない
            // ルール：祝日も処理しない（ロジックの一貫性を保つ）
            if (holidayService.isRestDay(date)) {
                continue;
            }

            // ルール2：既にレコードがある日は上書きしない（ステータスが "-" でも空でもないレコード）
            if (existingDates.contains(date)) {
                continue;
            }

            // レコードを取得または作成
            Attendance attendance = existingAttendances.stream()
                    .filter(a -> a.getDate().equals(date))
                    .findFirst()
                    .orElse(new Attendance(user, date));

            // ルール3&4：デフォルト値を設定
            attendance.setAgreement36Status(setting.getDefaultAgreement36Status() != null ? setting.getDefaultAgreement36Status() : "出社");
            attendance.setStartTime(setting.getDefaultStartTime());
            attendance.setEndTime(setting.getDefaultEndTime());
            // デフォルトの退勤が出勤以前なら日をまたぐ勤務とみなす
            attendance.setEndNextDay(setting.getDefaultStartTime() != null && setting.getDefaultEndTime() != null
                    && !setting.getDefaultEndTime().isAfter(setting.getDefaultStartTime()));
            
            // 休憩時間の変換 (minutes to LocalTime)
            Integer breakMin = setting.getDefaultBreakMinutes();
            if (breakMin != null) {
                attendance.setBreakTime(LocalTime.of(breakMin / 60, breakMin % 60));
            } else {
                attendance.setBreakTime(LocalTime.of(1, 0));
            }

            // ルール5：実働時間と残業時間は updateAttendance → calculateWorkHours でまとめて計算する
            updateAttendance(attendance);
        }
    }
    
    private void validateAttendance(Attendance attendance) {
        if (attendance.getUser() == null) {
            throw new RuntimeException("attendance.error.invalid");
        }
        if (attendance.getDate() == null) {
            throw new RuntimeException("attendance.error.invalid");
        }
        
        Optional<Attendance> existing = attendanceRepository.findByUserAndDate(attendance.getUser(), attendance.getDate());
        if (existing.isPresent() && !existing.get().getId().equals(attendance.getId())) {
            throw new BusinessException("attendance.error.duplicateDate", attendance.getDate());
        }

        // 退勤は出勤より後（日をまたぐ場合は「翌日」を指定し、勤務は 24 時間以内）
        if (isWorkingRecord(attendance)) {
            LocalTime start = attendance.getStartTime();
            LocalTime end = attendance.getEndTime();
            if (!attendance.isEndNextDay() && !end.isAfter(start)) {
                throw new IllegalArgumentException("attendance.error.endBeforeStart");
            }
            if (attendance.isEndNextDay() && end.isAfter(start)) {
                throw new IllegalArgumentException("attendance.error.shiftTooLong");
            }
        }

        String status = attendance.getAgreement36Status();
        if (STATUS_SPECIAL_LEAVE.equals(status)
                && (attendance.getRemarks() == null || attendance.getRemarks().isBlank())) {
            throw new IllegalArgumentException("attendance.error.specialLeaveReasonRequired");
        }
        if (isSubstituteStatus(status)) {
            validateSubstituteDate(attendance);
        } else {
            attendance.setSubstituteDate(null);
        }
    }

    /**
     * 振休・代休の休日出勤日のチェック。
     * <ul>
     *   <li>本人がその日（休日）に出社・在宅で実際に勤務していること</li>
     *   <li>振休：休日出勤日の前後 {@code substituteWindowDays} 日以内（事前に振り替える制度のため前後どちらも可）</li>
     *   <li>代休：休日出勤日より後で、{@code compensatoryValidityDays} 日以内</li>
     *   <li>1 つの休日出勤日は、振休・代休のどちらか 1 日にしか使えない</li>
     * </ul>
     */
    private void validateSubstituteDate(Attendance attendance) {
        LocalDate substituteDate = attendance.getSubstituteDate();
        LocalDate date = attendance.getDate();
        if (substituteDate == null) {
            throw new IllegalArgumentException("attendance.error.substituteDateRequired");
        }
        Attendance holidayWork = attendanceRepository.findByUserAndDate(attendance.getUser(), substituteDate).orElse(null);
        boolean worked = holidayWork != null && isWorkDayStatus(holidayWork.getAgreement36Status())
                && holidayWork.getWorkHours() != null && holidayWork.getWorkHours().compareTo(BigDecimal.ZERO) > 0;
        if (substituteDate.equals(date) || !holidayService.isRestDay(substituteDate) || !worked) {
            throw new BusinessException("attendance.error.substituteNotHolidayWork", substituteDate.toString());
        }
        long days = ChronoUnit.DAYS.between(substituteDate, date);
        if (STATUS_COMPENSATORY_LEAVE.equals(attendance.getAgreement36Status())) {
            if (days <= 0 || days > compensatoryValidityDays) {
                throw new BusinessException("attendance.error.compensatoryOutOfRange", compensatoryValidityDays);
            }
        } else if (Math.abs(days) > substituteWindowDays) {
            throw new BusinessException("attendance.error.substituteOutOfRange", substituteWindowDays);
        }
        for (Attendance other : attendanceRepository.findByUserAndSubstituteDate(attendance.getUser(), substituteDate)) {
            if (!java.util.Objects.equals(other.getId(), attendance.getId())
                    && isSubstituteStatus(other.getAgreement36Status())) {
                throw new BusinessException("attendance.error.substituteAlreadyUsed",
                        substituteDate.toString(), other.getDate().toString());
            }
        }
    }

    /**
     * 振休・代休に使える休日出勤日の候補（指定月の画面用）：対象期間内に本人が休日に勤務した日。
     * 値は、その日を既に使っている振休・代休の日（未使用なら null）。
     */
    public Map<LocalDate, LocalDate> substituteCandidates(User user, YearMonth month) {
        LocalDate from = month.atDay(1).minusDays(Math.max(compensatoryValidityDays, substituteWindowDays));
        LocalDate to = month.atEndOfMonth().plusDays(substituteWindowDays);
        List<Attendance> records = getAttendancesByUserAndDateRange(user, from, to);
        Map<LocalDate, LocalDate> usedBy = new HashMap<>();
        for (Attendance record : records) {
            if (isSubstituteStatus(record.getAgreement36Status()) && record.getSubstituteDate() != null) {
                usedBy.put(record.getSubstituteDate(), record.getDate());
            }
        }
        Map<LocalDate, LocalDate> candidates = new java.util.TreeMap<>();
        for (Attendance record : records) {
            if (record.getDate() != null && holidayService.isRestDay(record.getDate())
                    && isWorkDayStatus(record.getAgreement36Status())
                    && record.getWorkHours() != null && record.getWorkHours().compareTo(BigDecimal.ZERO) > 0) {
                candidates.put(record.getDate(), usedBy.get(record.getDate()));
            }
        }
        return candidates;
    }

    /** 時刻から勤務時間を計算する対象か（-/欠勤/有給 以外で、出勤・退勤が両方ある） */
    private static boolean isWorkingRecord(Attendance attendance) {
        String status = attendance.getAgreement36Status();
        return !isNonWorkingStatus(status)
                && attendance.getStartTime() != null && attendance.getEndTime() != null;
    }

    /** この休日出勤日が振休で振り替えられているか（振り替えた休日は平日として計算する） */
    private boolean isSubstitutedRestDay(Attendance attendance) {
        return attendance.getUser() != null && attendance.getDate() != null
                && attendanceRepository.existsByUserAndSubstituteDateAndAgreement36Status(
                        attendance.getUser(), attendance.getDate(), STATUS_SUBSTITUTE_HOLIDAY);
    }

    /** 拘束時間（分）。翌日退勤なら 24 時間を加える。旧データで翌日指定がなく退勤が出勤より前の場合も日またぎとみなす */
    static long shiftMinutes(Attendance attendance) {
        long minutes = ChronoUnit.MINUTES.between(attendance.getStartTime(), attendance.getEndTime());
        if (attendance.isEndNextDay() || minutes < 0) {
            minutes += 1440;
        }
        return minutes;
    }

    /**
     * 労働基準法の休憩の最低時間（分）：労働時間が 6 時間を超えると 45 分、8 時間を超えると 60 分。
     *
     * @param workMinutes 休憩を除いた労働時間（分）
     */
    static long legalMinimumBreakMinutes(long workMinutes) {
        if (workMinutes > 8 * 60) {
            return 60;
        }
        if (workMinutes > 6 * 60) {
            return 45;
        }
        return 0;
    }

    /** 深夜（22:00〜翌 5:00）に含まれる分数。出勤日の前日 22 時から翌々日 5 時までの枠と重なる分を合計する */
    static long nightMinutes(long startMinute, long endMinute) {
        long total = 0;
        for (int day = 0; day <= 2; day++) {
            long nightStart = day * 1440L - 120; // 前日 22:00
            long nightEnd = day * 1440L + 300;   // 当日 5:00
            total += Math.max(0, Math.min(endMinute, nightEnd) - Math.max(startMinute, nightStart));
        }
        return total;
    }
    
    /**
     * 実働時間・残業時間・深夜時間・休日時間は常にサーバー側で計算し、フロントエンドから送られた値は使わない。
     * <ul>
     *   <li>休憩：入力値が法定の最低時間（6h 超 45 分、8h 超 60 分）に満たなければ、最低時間に引き上げて保存する</li>
     *   <li>実働 = 退勤 − 出勤（翌日退勤なら 24 時間を加算）− 休憩</li>
     *   <li>残業 = 休日（土日祝・会社休日）は実働のすべて、平日は 8 時間を超えた分</li>
     *   <li>深夜 = 22:00〜翌 5:00 に含まれる勤務時間（休憩の時間帯は分からないため差し引かない。実働を上限とする）</li>
     *   <li>休日 = 法定休日（日曜日。36 協定の集計と同じ）の実働。出勤日で判定する</li>
     * </ul>
     * フロントエンドのプレビュー（attendance.html、layout.html のスマホ用編集）も同じルールを使う。
     */
    private void calculateWorkHours(Attendance attendance) {
        if (!isWorkingRecord(attendance)) {
            attendance.setWorkHours(BigDecimal.ZERO);
            attendance.setOvertimeHours(BigDecimal.ZERO);
            attendance.setNightHours(BigDecimal.ZERO);
            attendance.setHolidayHours(BigDecimal.ZERO);
            return;
        }
        if (attendance.getEndNextDay() == null) {
            // 旧データ：退勤が出勤以前なら日をまたぐ勤務だった
            attendance.setEndNextDay(!attendance.getEndTime().isAfter(attendance.getStartTime()));
        }

        long shift = shiftMinutes(attendance);
        long enteredBreak = attendance.getBreakTime() == null ? 0 : attendance.getBreakTime().toSecondOfDay() / 60;
        long requiredBreak = legalMinimumBreakMinutes(shift - enteredBreak);
        long breakMinutes = Math.max(enteredBreak, requiredBreak);
        if (breakMinutes != enteredBreak) {
            attendance.setBreakTime(LocalTime.of((int) (breakMinutes / 60), (int) (breakMinutes % 60)));
        }
        long minutes = Math.max(0, shift - breakMinutes);

        BigDecimal hours = toHours(minutes);
        attendance.setWorkHours(hours);

        long startMinute = attendance.getStartTime().toSecondOfDay() / 60;
        attendance.setNightHours(toHours(Math.min(minutes, nightMinutes(startMinute, startMinute + shift))));
        // 振休で振り替えた休日は平日として扱う（休日労働にも、全時間の残業にもしない）
        boolean restDay = holidayService.isRestDay(attendance.getDate());
        boolean substituted = restDay && isSubstitutedRestDay(attendance);
        attendance.setHolidayHours(attendance.getDate().getDayOfWeek() == DayOfWeek.SUNDAY && !substituted
                ? hours : BigDecimal.ZERO.setScale(2));

        if (restDay && !substituted) {
            attendance.setOvertimeHours(hours);
        } else {
            BigDecimal overtime = hours.subtract(STANDARD_WORK_HOURS);
            attendance.setOvertimeHours(overtime.compareTo(BigDecimal.ZERO) > 0 ? overtime : BigDecimal.ZERO);
        }
    }

    /**
     * 現行ルールで、未ロックの月（月報が未提出または拒否）の実働時間と残業時間をすべて再計算する。
     * 提出済み・承認済みの月はそのままにし、承認された数値が変わらないようにする。変更があったレコード数を返す。
     */
    public int recalculateUnlockedAttendances() {
        Map<String, Boolean> lockedMonths = new HashMap<>();
        int changed = 0;
        for (Attendance attendance : attendanceRepository.findAll()) {
            User user = attendance.getUser();
            LocalDate date = attendance.getDate();
            if (user == null || date == null) {
                continue;
            }
            boolean locked = lockedMonths.computeIfAbsent(user.getId() + ":" + YearMonth.from(date),
                    key -> isMonthLocked(user, date.getYear(), date.getMonthValue()));
            if (locked) {
                // 承認済みの数値（実働・残業・休憩）は変えず、新しい項目（翌日・深夜・休日）が空なら補う
                if (fillNewColumnsKeepingHours(attendance)) {
                    attendanceRepository.save(attendance);
                    changed++;
                }
                continue;
            }

            BigDecimal oldWorkHours = attendance.getWorkHours();
            BigDecimal oldOvertimeHours = attendance.getOvertimeHours();
            BigDecimal oldNightHours = attendance.getNightHours();
            BigDecimal oldHolidayHours = attendance.getHolidayHours();
            LocalTime oldBreakTime = attendance.getBreakTime();
            Boolean oldEndNextDay = attendance.getEndNextDay();
            calculateWorkHours(attendance);
            if (!sameHours(oldWorkHours, attendance.getWorkHours())
                    || !sameHours(oldOvertimeHours, attendance.getOvertimeHours())
                    || !sameHours(oldNightHours, attendance.getNightHours())
                    || !sameHours(oldHolidayHours, attendance.getHolidayHours())
                    || !Objects.equals(oldBreakTime, attendance.getBreakTime())
                    || !Objects.equals(oldEndNextDay, attendance.getEndNextDay())) {
                attendanceRepository.save(attendance);
                changed++;
            }
        }
        return changed;
    }

    /** ロック済みの月のレコード：実働・残業・休憩はそのままで、翌日フラグ・深夜・休日だけを補う。変更があれば true */
    private boolean fillNewColumnsKeepingHours(Attendance attendance) {
        if (attendance.getEndNextDay() != null && attendance.getNightHours() != null
                && attendance.getHolidayHours() != null) {
            return false;
        }
        if (!isWorkingRecord(attendance)) {
            attendance.setEndNextDay(attendance.getEndNextDay() != null && attendance.getEndNextDay());
            attendance.setNightHours(BigDecimal.ZERO);
            attendance.setHolidayHours(BigDecimal.ZERO);
            return true;
        }
        if (attendance.getEndNextDay() == null) {
            attendance.setEndNextDay(!attendance.getEndTime().isAfter(attendance.getStartTime()));
        }
        BigDecimal workHours = attendance.getWorkHours() == null ? BigDecimal.ZERO : attendance.getWorkHours();
        long workMinutes = workHours.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();
        long startMinute = attendance.getStartTime().toSecondOfDay() / 60;
        attendance.setNightHours(toHours(Math.min(workMinutes,
                nightMinutes(startMinute, startMinute + shiftMinutes(attendance)))));
        attendance.setHolidayHours(attendance.getDate().getDayOfWeek() == DayOfWeek.SUNDAY
                ? workHours : BigDecimal.ZERO.setScale(2));
        return true;
    }

    private static BigDecimal toHours(long minutes) {
        return new BigDecimal(minutes).divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
    }

    private boolean sameHours(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }
    
    private void determineAgreement36Status(Attendance attendance) {
        // 鉄則：ステータスに値がある場合（ユーザーが入力した"-"を含む）、サーバー側で勝手に書き換えてはならない
        if (attendance.getAgreement36Status() != null && !attendance.getAgreement36Status().trim().isEmpty()) {
            // 既に"-"の場合は変更しない
            if ("-".equals(attendance.getAgreement36Status().trim())) {
                return;
            }
            return;
        }

        boolean isRestDay = holidayService.isRestDay(attendance.getDate());
        BigDecimal workHours = attendance.getWorkHours() != null ? attendance.getWorkHours() : BigDecimal.ZERO;
        boolean hasNightShift = attendance.getNightHours() != null
                && attendance.getNightHours().compareTo(BigDecimal.ZERO) > 0;
        
        if (workHours.compareTo(BigDecimal.ZERO) <= 0) {
            attendance.setAgreement36Status(isRestDay ? "休日" : "未出勤");
        } else if (isRestDay) {
            if (hasNightShift) {
                attendance.setAgreement36Status("休日深夜");
            } else {
                attendance.setAgreement36Status("休日");
            }
        } else {
            if (workHours.compareTo(OVERTIME_THRESHOLD) > 0) {
                if (hasNightShift) {
                    attendance.setAgreement36Status("加班深夜");
                } else {
                    attendance.setAgreement36Status("加班");
                }
            } else {
                if (hasNightShift) {
                    attendance.setAgreement36Status("深夜");
                } else {
                    attendance.setAgreement36Status("正常");
                }
            }
        }
    }
    
    public record MonthlyStatistics(
            BigDecimal workHours,
            BigDecimal overtimeHours,
            int workDays,
            BigDecimal nightHours,
            BigDecimal holidayHours) {
    }

    public record MonthlyTrend(
            String label,
            BigDecimal workHours,
            BigDecimal overtimeHours,
            int workDays,
            int workHoursPercent,
            int overtimeHoursPercent) {
    }
}
