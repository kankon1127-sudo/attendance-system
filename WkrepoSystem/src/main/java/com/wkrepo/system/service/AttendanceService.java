package com.wkrepo.system.service;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.AttendanceRepository;
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
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class AttendanceService {
    
    @Autowired
    private AttendanceRepository attendanceRepository;
    
    @Autowired
    private HolidayService holidayService;
    
    @Autowired
    private PaidLeaveService paidLeaveService;
    
    private static final LocalTime NIGHT_SHIFT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_SHIFT_END = LocalTime.of(5, 0);
    private static final BigDecimal STANDARD_WORK_HOURS = new BigDecimal("8.0");
    private static final BigDecimal OVERTIME_THRESHOLD = new BigDecimal("8.0");
    
    public Attendance createAttendance(Attendance attendance) {
        validateAttendance(attendance);
        calculateWorkHours(attendance);
        determineAgreement36Status(attendance);
        return attendanceRepository.save(attendance);
    }
    
    public Attendance updateAttendance(Attendance attendance) {
        // 核心修复：如果 ID 为空但该日期已有记录，自动关联 ID 确保是“更新”而非“新增”
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
        StringBuilder message = new StringBuilder("勤怠で有給を選択した日付は、年休申請しリーダー承認後でないと月報を提出できません。");
        if (!missingApplicationDates.isEmpty()) {
            message.append(" 未申請: ")
                    .append(missingApplicationDates.stream().map(formatter::format).collect(java.util.stream.Collectors.joining(", ")))
                    .append("。");
        }
        if (!unapprovedDates.isEmpty()) {
            message.append(" リーダー未承認: ")
                    .append(unapprovedDates.stream().map(formatter::format).collect(java.util.stream.Collectors.joining(", ")))
                    .append("。");
        }
        throw new RuntimeException(message.toString());
    }
    
    public void deleteAttendance(Long id) {
        attendanceRepository.deleteById(id);
    }
    
    public Attendance getAttendanceById(Long id) {
        return attendanceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("考勤记录不存在，ID: " + id));
    }
    
    public Attendance getAttendanceByUserAndDate(User user, LocalDate date) {
        return attendanceRepository.findByUserAndDate(user, date)
                .orElse(null);
    }
    
    public List<Attendance> getAttendancesByUserAndDateRange(User user, LocalDate startDate, LocalDate endDate) {
        return attendanceRepository.findByUserAndDateBetweenOrderByDateAsc(user, startDate, endDate);
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
     * 一括デフォルト入力逻辑
     */
    public void bulkApplyDefaults(User user, int year, int month, DefaultAttendanceSetting setting) {
        if (setting == null) {
            throw new RuntimeException("デフォルト設定が見つかりません。");
        }

        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDate startDate = yearMonth.atDay(1);
        LocalDate endDate = yearMonth.atEndOfMonth();

        // 获取已有记录
        List<Attendance> existingAttendances = getAttendancesByUserAndDateRange(user, startDate, endDate);
        java.util.Set<LocalDate> existingDates = existingAttendances.stream()
                .filter(a -> a.getAgreement36Status() != null && !"-".equals(a.getAgreement36Status()) && !"".equals(a.getAgreement36Status()))
                .map(Attendance::getDate)
                .collect(java.util.stream.Collectors.toSet());

        for (int i = 1; i <= yearMonth.lengthOfMonth(); i++) {
            LocalDate date = yearMonth.atDay(i);
            
            // 规则1：土曜日和日曜日不处理
            // 规则：如果是节假日也不处理 (保持逻辑一致性)
            if (holidayService.isRestDay(date)) {
                continue;
            }

            // 规则2：已经存在记录的日期不覆盖 (指状态不为 "-" 或 空 の记录)
            if (existingDates.contains(date)) {
                continue;
            }

            // 获取或创建记录
            Attendance attendance = existingAttendances.stream()
                    .filter(a -> a.getDate().equals(date))
                    .findFirst()
                    .orElse(new Attendance(user, date));

            // 规则3&4：设置默认値
            attendance.setAgreement36Status(setting.getDefaultAgreement36Status() != null ? setting.getDefaultAgreement36Status() : "出社");
            attendance.setStartTime(setting.getDefaultStartTime());
            attendance.setEndTime(setting.getDefaultEndTime());
            
            // 处理休息時間 (minutes to LocalTime)
            Integer breakMin = setting.getDefaultBreakMinutes();
            if (breakMin != null) {
                attendance.setBreakTime(LocalTime.of(breakMin / 60, breakMin % 60));
            } else {
                attendance.setBreakTime(LocalTime.of(1, 0));
            }

            // 规则5：保持现有工時計算ロジック (調用 updateAttendance 会自動トリガ calculateWorkHours)
            // 清除工時フィールド以強制再計算
            attendance.setWorkHours(null);
            attendance.setOvertimeHours(null);
            
            updateAttendance(attendance);
        }
    }
    
    private void validateAttendance(Attendance attendance) {
        if (attendance.getUser() == null) {
            throw new RuntimeException("用户不能为空");
        }
        if (attendance.getDate() == null) {
            throw new RuntimeException("日期不能为空");
        }
        
        Optional<Attendance> existing = attendanceRepository.findByUserAndDate(attendance.getUser(), attendance.getDate());
        if (existing.isPresent() && !existing.get().getId().equals(attendance.getId())) {
            throw new RuntimeException("该日期已存在考勤记录: " + attendance.getDate());
        }
    }
    
    private void calculateWorkHours(Attendance attendance) {
        // 【斩草除根铁律】：只要 Controller 已经传入了工时或加班时间，后端绝对禁止重新计算！
        // 但有一个例外：如果状态是"-"，无论其他字段如何，都应将工时设为0
        String status = attendance.getAgreement36Status();
        if (status != null && "-".equals(status)) {
            attendance.setWorkHours(BigDecimal.ZERO);
            attendance.setOvertimeHours(BigDecimal.ZERO);
            return; // 直接返回，不再进行任何计算
        }
        
        // 正常流程：分别判断，防止其中一个为 null 导致另一个被意外覆盖
        boolean hasWorkHours = attendance.getWorkHours() != null;
        boolean hasOvertimeHours = attendance.getOvertimeHours() != null;

        if (hasWorkHours && hasOvertimeHours) {
            return;
        }

        // 只有在字段确实缺失的情况下，才基于打卡时间进行"补漏"计算
        if (attendance.getStartTime() != null && attendance.getEndTime() != null) {
            long minutes = ChronoUnit.MINUTES.between(attendance.getStartTime(), attendance.getEndTime());
            if (minutes < 0) minutes += 1440;
            
            BigDecimal hours = new BigDecimal(minutes)
                    .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
            
            // 仅在字段为 null 时填充，绝不覆盖已有值（哪怕是 0）
            if (!hasWorkHours) {
                attendance.setWorkHours(hours);
            }
            
            if (!hasOvertimeHours) {
                if (holidayService.isRestDay(attendance.getDate())) {
                    attendance.setOvertimeHours(hours);
                } else {
                    BigDecimal overtime = hours.subtract(STANDARD_WORK_HOURS);
                    attendance.setOvertimeHours(overtime.compareTo(BigDecimal.ZERO) > 0 ? overtime : BigDecimal.ZERO);
                }
            }
        } else {
            // 如果既没有传入工时，又没有打卡时间，才兜底填充 0，防止数据库报错
            if (!hasWorkHours) attendance.setWorkHours(BigDecimal.ZERO);
            if (!hasOvertimeHours) attendance.setOvertimeHours(BigDecimal.ZERO);
        }
    }
    
    private void determineAgreement36Status(Attendance attendance) {
        // 铁律：只要状态位已有值（包括用户录入的"-"），后端绝对禁止擅自改写状态！
        if (attendance.getAgreement36Status() != null && !attendance.getAgreement36Status().trim().isEmpty()) {
            // 如果已经是"-"状态，不要修改它
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
    
    public boolean validateAgreement36Rules(User user, LocalDate startDate, LocalDate endDate) {
        List<Attendance> attendances = attendanceRepository.findByUserAndDateBetweenOrderByDateAsc(user, startDate, endDate);
        
        BigDecimal totalOvertimeHours = BigDecimal.ZERO;
        BigDecimal totalHolidayHours = BigDecimal.ZERO;
        
        for (Attendance attendance : attendances) {
            String status = attendance.getAgreement36Status();
            BigDecimal workHours = attendance.getWorkHours() != null ? attendance.getWorkHours() : BigDecimal.ZERO;
            
            if (status != null && (status.contains("加班") || status.contains("深夜"))) {
                if (status.contains("休日")) {
                    totalHolidayHours = totalHolidayHours.add(workHours);
                } else {
                    BigDecimal overtime = workHours.subtract(STANDARD_WORK_HOURS);
                    if (overtime.compareTo(BigDecimal.ZERO) > 0) {
                        totalOvertimeHours = totalOvertimeHours.add(overtime);
                    }
                }
            }
        }
        
        BigDecimal monthlyOvertimeLimit = new BigDecimal("45.0");
        BigDecimal monthlyHolidayLimit = new BigDecimal("40.0");
        
        if (totalOvertimeHours.compareTo(monthlyOvertimeLimit) > 0) {
            throw new RuntimeException("36协定违规：月度加班時間超過制限（" + totalOvertimeHours + "時間，制限：" + monthlyOvertimeLimit + "時間）");
        }
        
        if (totalHolidayHours.compareTo(monthlyHolidayLimit) > 0) {
            throw new RuntimeException("36协定违规：月度休日工作時間超過制限（" + totalHolidayHours + "時間，制限：" + monthlyHolidayLimit + "時間）");
        }
        
        return true;
    }
}
