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
    
    List<Attendance> findByUserAndDateBetweenOrderByDateAsc(User user, LocalDate startDate, LocalDate endDate);
    
    List<Attendance> findByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);
    
    @Query("SELECT a FROM Attendance a WHERE a.user.id = :userId AND a.date = :date")
    Optional<Attendance> findByUserIdAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);
    
    @Query("SELECT MIN(a.date) FROM Attendance a WHERE a.user = :user")
    LocalDate findFirstDateByUser(@Param("user") User user);
    
    boolean existsByUserAndDate(User user, LocalDate date);
}
