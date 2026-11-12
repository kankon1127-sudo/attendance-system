package com.wkrepo.system.repository;

import com.wkrepo.system.entity.Attendance;
import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    
    Optional<Attendance> findByUserAndDate(User user, LocalDate date);

    /** 振休・代休：この休日出勤日を使っている勤怠 */
    List<Attendance> findByUserAndSubstituteDate(User user, LocalDate substituteDate);

    /** この休日出勤日が振休で振り替えられているか（振り替えた休日は平日として計算する） */
    boolean existsByUserAndSubstituteDateAndAgreement36Status(User user, LocalDate substituteDate, String status);
    
    List<Attendance> findByUserAndDateBetweenOrderByDateAsc(User user, LocalDate startDate, LocalDate endDate);
    
    List<Attendance> findByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);
    
    @Query("SELECT a FROM Attendance a WHERE a.user.id = :userId AND a.date = :date")
    Optional<Attendance> findByUserIdAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);
    
    @Query("SELECT MIN(a.date) FROM Attendance a WHERE a.user = :user")
    LocalDate findFirstDateByUser(@Param("user") User user);
    
    boolean existsByUserAndDate(User user, LocalDate date);
}
