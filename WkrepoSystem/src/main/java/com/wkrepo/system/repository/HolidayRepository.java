package com.wkrepo.system.repository;

import com.wkrepo.system.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);

    boolean existsByDate(LocalDate date);
}
