package com.wkrepo.system.service;

import com.wkrepo.system.dto.AttendanceAnomaly;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.MonthlyReport;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.enums.ApplicationStatus;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 上長のチーム一覧。部下 = users.manager_id をたどった直属・間接の部下（承認経路と同じ）。
 * ADMIN は全員（自分を除く有効な社員）を対象にする。
 */
@Service
public class TeamService {

    private final UserRepository userRepository;
    private final AttendanceService attendanceService;
    private final Agreement36Service agreement36Service;
    private final HolidayService holidayService;
    private final MonthlyReportRepository monthlyReportRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TeamService(UserRepository userRepository, AttendanceService attendanceService,
                       Agreement36Service agreement36Service, HolidayService holidayService,
                       MonthlyReportRepository monthlyReportRepository) {
        this(userRepository, attendanceService, agreement36Service, holidayService, monthlyReportRepository,
                Clock.systemDefaultZone());
    }

    TeamService(UserRepository userRepository, AttendanceService attendanceService,
                Agreement36Service agreement36Service, HolidayService holidayService,
                MonthlyReportRepository monthlyReportRepository, Clock clock) {
        this.userRepository = userRepository;
        this.attendanceService = attendanceService;
        this.agreement36Service = agreement36Service;
        this.holidayService = holidayService;
        this.monthlyReportRepository = monthlyReportRepository;
        this.clock = clock;
    }

    /** チーム一覧を使えるロール（部長・管理者） */
    public static boolean canUseTeamView(User user) {
        return user != null && ("MANAGER".equals(user.getRole()) || "ADMIN".equals(user.getRole()));
    }

    /**
     * 部下（有効な社員のみ、社員番号順）。値は直属の部下なら true。
     * manager_id が循環していても止まるよう、たどった人を記録する。
     */
    public Map<User, Boolean> members(User leader) {
        Map<User, Boolean> result = new LinkedHashMap<>();
        if (!canUseTeamView(leader)) {
            return result;
        }
        List<User> found = new ArrayList<>();
        Set<Long> directIds = new HashSet<>();
        if ("ADMIN".equals(leader.getRole())) {
            for (User user : userRepository.findAll()) {
                if (!Objects.equals(user.getId(), leader.getId())) {
                    found.add(user);
                    if (Objects.equals(user.getManagerId(), leader.getId())) {
                        directIds.add(user.getId());
                    }
                }
            }
        } else {
            Set<Long> visited = new HashSet<>();
            visited.add(leader.getId());
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(leader.getId());
            while (!queue.isEmpty()) {
                Long bossId = queue.poll();
                for (User subordinate : userRepository.findByManagerIdIn(List.of(bossId))) {
                    if (visited.add(subordinate.getId())) {
                        found.add(subordinate);
                        queue.add(subordinate.getId());
                        if (Objects.equals(bossId, leader.getId())) {
                            directIds.add(subordinate.getId());
                        }
                    }
                }
            }
        }
        found.stream()
                .filter(user -> !"INACTIVE".equals(user.getStatus()))
                .sorted(Comparator.comparing(User::getEmployeeId, Comparator.nullsLast(String::compareTo)))
                .forEach(user -> result.put(user, directIds.contains(user.getId())));
        return result;
    }

    /** target が leader のチームの一員か（部下の勤怠を見てよいか） */
    public boolean isMember(User leader, Long targetUserId) {
        return targetUserId != null && members(leader).keySet().stream()
                .anyMatch(member -> Objects.equals(member.getId(), targetUserId));
    }

    /** チーム一覧の 1 行 */
    public record MemberSummary(User member, boolean direct, String managerName,
                                int workDays, BigDecimal workHours, BigDecimal overtimeHours,
                                BigDecimal holidayWorkHours, BigDecimal nightHours,
                                int agreement36Alerts, boolean agreement36Error,
                                int anomalies, int missingDays, ApplicationStatus reportStatus) {

        /** 何か確認が必要か（36 協定・異常・未入力） */
        public boolean needsAttention() {
            return agreement36Alerts > 0 || anomalies > 0 || missingDays > 0;
        }
    }

    public List<MemberSummary> summarize(User leader, YearMonth month) {
        Map<User, Boolean> members = members(leader);
        Map<Long, String> namesById = new HashMap<>();
        for (User user : userRepository.findAll()) {
            namesById.put(user.getId(), user.getName());
        }
        List<MemberSummary> summaries = new ArrayList<>();
        for (Map.Entry<User, Boolean> entry : members.entrySet()) {
            User member = entry.getKey();
            List<Attendance> attendances = attendanceService.getAttendancesByUserAndDateRange(
                    member, month.atDay(1), month.atEndOfMonth());
            AttendanceService.MonthlyStatistics statistics = attendanceService.calculateMonthlyStatistics(attendances, month);
            Agreement36Service.Result agreement36 = agreement36Service.check(member, month);
            List<AttendanceAnomaly> anomalies = attendanceService.findMonthlyAnomalies(member, attendances);
            ApplicationStatus reportStatus = monthlyReportRepository
                    .findByUserIdAndYearAndMonth(member.getId(), month.getYear(), month.getMonthValue())
                    .map(MonthlyReport::getStatus)
                    .orElse(null);
            summaries.add(new MemberSummary(member, entry.getValue(), namesById.get(member.getManagerId()),
                    statistics.workDays(), statistics.workHours(), agreement36.monthlyOvertime(),
                    agreement36.monthlyHolidayWork(), statistics.nightHours(),
                    agreement36.alerts().size(), agreement36.hasErrors(),
                    anomalies.size(), countMissingDays(attendances, month), reportStatus));
        }
        return summaries;
    }

    /** 今日までの勤務日（休日を除く）のうち、区分が入力されていない日数 */
    int countMissingDays(List<Attendance> attendances, YearMonth month) {
        Set<LocalDate> entered = new HashSet<>();
        for (Attendance attendance : attendances) {
            String status = attendance.getAgreement36Status();
            if (attendance.getDate() != null && status != null && !status.isBlank() && !"-".equals(status.trim())) {
                entered.add(attendance.getDate());
            }
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate last = month.atEndOfMonth().isBefore(today) ? month.atEndOfMonth() : today;
        int missing = 0;
        for (LocalDate date = month.atDay(1); !date.isAfter(last); date = date.plusDays(1)) {
            if (!holidayService.isRestDay(date) && !entered.contains(date)) {
                missing++;
            }
        }
        return missing;
    }
}
