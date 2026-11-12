package com.wkrepo.system.service;

import com.wkrepo.system.TestData;
import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.User;
import com.wkrepo.system.repository.MonthlyReportRepository;
import com.wkrepo.system.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** チーム一覧：部下の範囲（直属・間接、停止中は除く、ADMIN は全員）と未入力日数 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12); // 木曜日

    @Mock
    private UserRepository userRepository;
    @Mock
    private AttendanceService attendanceService;
    @Mock
    private Agreement36Service agreement36Service;
    @Mock
    private MonthlyReportRepository monthlyReportRepository;

    private TeamService service;

    private final User admin = TestData.user(1L, "ADMIN", "管理部");
    private final User director = TestData.user(2L, "MANAGER", "開発部");   // 部長
    private final User leader = TestData.user(3L, "MANAGER", "開発部");     // 課長（部長の部下）
    private final User staff = TestData.user(4L, "GENERAL", "開発部");      // 課長の部下
    private final User retired = TestData.user(5L, "GENERAL", "開発部");    // 停止中
    private final User otherDept = TestData.user(6L, "GENERAL", "営業部");  // 部長の直属だが別部署
    private final User unrelated = TestData.user(7L, "GENERAL", "開発部");  // 上司なし
    private List<User> users;

    @BeforeEach
    void setUp() {
        leader.setManagerId(director.getId());
        staff.setManagerId(leader.getId());
        retired.setManagerId(leader.getId());
        retired.setStatus("INACTIVE");
        otherDept.setManagerId(director.getId());
        users = List.of(admin, director, leader, staff, retired, otherDept, unrelated);
        when(userRepository.findAll()).thenReturn(users);
        when(userRepository.findByManagerIdIn(any())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            // List.of(...).contains(null) は例外になるため、上司なし（null）の人は Objects.equals で比較する
            return users.stream()
                    .filter(user -> ids.stream().anyMatch(id -> Objects.equals(id, user.getManagerId())))
                    .toList();
        });
        service = new TeamService(userRepository, attendanceService, agreement36Service, TestData.holidayService(),
                monthlyReportRepository, Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                ZoneId.systemDefault()));
    }

    @Test
    void membersAreDirectAndIndirectSubordinates() {
        Map<User, Boolean> members = service.members(director);

        assertThat(members.keySet()).containsExactly(leader, staff, otherDept); // 社員番号順、停止中は除く
        assertThat(members.get(leader)).isTrue();
        assertThat(members.get(otherDept)).isTrue();
        assertThat(members.get(staff)).isFalse(); // 間接
    }

    @Test
    void middleManagerSeesOnlyTheirOwnSubordinates() {
        assertThat(service.members(leader).keySet()).containsExactly(staff);
        assertThat(service.isMember(leader, staff.getId())).isTrue();
        assertThat(service.isMember(leader, otherDept.getId())).isFalse();
        assertThat(service.isMember(leader, unrelated.getId())).isFalse();
    }

    @Test
    void adminSeesEveryoneExceptThemselves() {
        assertThat(service.members(admin).keySet()).containsExactly(director, leader, staff, otherDept, unrelated);
    }

    @Test
    void generalAndSoumuHaveNoTeamView() {
        assertThat(service.members(staff)).isEmpty();
        assertThat(TeamService.canUseTeamView(TestData.user(9L, "SOUMU", "総務部"))).isFalse();
    }

    @Test
    void managerCycleDoesNotLoopForever() {
        director.setManagerId(staff.getId()); // 誤った設定：部長 → 課長 → 社員 → 部長

        assertThat(service.members(director).keySet()).containsExactlyInAnyOrder(leader, staff, otherDept);
    }

    @Test
    void missingDaysCountOnlyPastWorkdaysWithoutStatus() {
        // 2026/11/1〜11/12 の勤務日：2,4,5,6,9,10,11,12（3 日は文化の日、土日を除く）= 8 日
        List<Attendance> attendances = List.of(
                TestData.attendance(staff, "2026-11-02", "出社", "09:00", "18:00", "01:00"),
                TestData.attendance(staff, "2026-11-04", "有給", null, null, null),
                TestData.attendance(staff, "2026-11-05", "-", null, null, null),          // 「-」は未入力扱い
                TestData.attendance(staff, "2026-11-08", "出社", "09:00", "12:00", null)); // 日曜（勤務日ではない）

        assertThat(service.countMissingDays(attendances, YearMonth.of(2026, 11))).isEqualTo(6);
        assertThat(service.countMissingDays(List.of(), YearMonth.of(2026, 12))).isZero(); // 未来の月
    }
}
