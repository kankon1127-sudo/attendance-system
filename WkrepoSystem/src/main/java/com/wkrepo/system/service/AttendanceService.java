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
    
    private static final LocalTime NIGHT_SHIFT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_SHIFT_END = LocalTime.of(5, 0);
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
        return attendanceRepository.save(attendance);
    }

    public static boolean isPaidLeaveStatus(String status) {
        return "有給".equals(status) || "有休".equals(status);
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
            if (!isPaidLeaveStatus(attendance.getAgreement36Status())) {
                continue;
            }
            LocalDate date = attendance.getDate();
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
        int totalWorkDays = 0;

        for (Attendance attendance : attendances) {
            if (attendance.getDate() == null || !YearMonth.from(attendance.getDate()).equals(yearMonth)) {
                continue;
            }
            String status = attendance.getAgreement36Status();
            boolean excludedStatus = "-".equals(status) || "欠勤".equals(status) || isPaidLeaveStatus(status);
            if (excludedStatus || status == null || status.isBlank()) {
                continue;
            }

            BigDecimal workHours = attendance.getWorkHours() == null
                    ? BigDecimal.ZERO : attendance.getWorkHours();
            BigDecimal overtimeHours = attendance.getOvertimeHours() == null
                    ? BigDecimal.ZERO : attendance.getOvertimeHours();
            totalWorkHours = totalWorkHours.add(workHours);
            totalOvertimeHours = totalOvertimeHours.add(overtimeHours);

            if (("出社".equals(status) || "在宅".equals(status))
                    && workHours.compareTo(BigDecimal.ZERO) > 0) {
                totalWorkDays++;
            }
        }
        return new MonthlyStatistics(totalWorkHours, totalOvertimeHours, totalWorkDays);
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

            if ("出社".equals(status) || "在宅".equals(status)) {
                if (attendance.getStartTime() == null || attendance.getEndTime() == null) {
                    anomalies.add(new AttendanceAnomaly(date, "attendance.anomaly.missingTime"));
                } else if (attendance.getBreakTime() != null) {
                    long shiftMinutes = ChronoUnit.MINUTES.between(
                            attendance.getStartTime(), attendance.getEndTime());
                    if (shiftMinutes < 0) {
                        shiftMinutes += 24 * 60;
                    }
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
    }
    
    /**
     * 実働時間と残業時間は常にサーバー側で出勤・退勤時刻と休憩時間から計算し、フロントエンドから送られた値は使わない。
     * 実働 = 退勤 − 出勤（日をまたぐ場合は 24 時間を加算）− 休憩。残業 = 休日は実働のすべて、平日は 8 時間を超えた分。
     * フロントエンドのプレビュー（attendance.html、layout.html のスマホ用編集）も同じルールを使う。
     */
    private void calculateWorkHours(Attendance attendance) {
        String status = attendance.getAgreement36Status();
        if ("-".equals(status) || "欠勤".equals(status) || isPaidLeaveStatus(status)
                || attendance.getStartTime() == null || attendance.getEndTime() == null) {
            attendance.setWorkHours(BigDecimal.ZERO);
            attendance.setOvertimeHours(BigDecimal.ZERO);
            return;
        }

        long minutes = ChronoUnit.MINUTES.between(attendance.getStartTime(), attendance.getEndTime());
        if (minutes < 0) minutes += 1440;
        if (attendance.getBreakTime() != null) {
            minutes -= attendance.getBreakTime().toSecondOfDay() / 60;
        }
        if (minutes < 0) minutes = 0;

        BigDecimal hours = new BigDecimal(minutes)
                .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
        attendance.setWorkHours(hours);

        if (holidayService.isRestDay(attendance.getDate())) {
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
                continue;
            }

            BigDecimal oldWorkHours = attendance.getWorkHours();
            BigDecimal oldOvertimeHours = attendance.getOvertimeHours();
            calculateWorkHours(attendance);
            if (!sameHours(oldWorkHours, attendance.getWorkHours())
                    || !sameHours(oldOvertimeHours, attendance.getOvertimeHours())) {
                attendanceRepository.save(attendance);
                changed++;
            }
        }
        return changed;
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
        boolean hasNightShift = hasNightShift(attendance.getStartTime(), attendance.getEndTime());
        
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
    
    
    private boolean hasNightShift(LocalTime startTime, LocalTime endTime) {
        if (startTime == null || endTime == null) {
            return false;
        }
        
        if (startTime.isAfter(endTime)) {
            return startTime.isAfter(NIGHT_SHIFT_START) || endTime.isBefore(NIGHT_SHIFT_END);
        } else {
            return startTime.isBefore(NIGHT_SHIFT_END) || endTime.isAfter(NIGHT_SHIFT_START);
        }
    }
    
    public record MonthlyStatistics(
            BigDecimal workHours,
            BigDecimal overtimeHours,
            int workDays) {
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
