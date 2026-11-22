package com.wkrepo.system.repository;

import com.wkrepo.system.entity.PaidLeaveGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PaidLeaveGrantRepository extends JpaRepository<PaidLeaveGrant, Long> {

    List<PaidLeaveGrant> findByUserIdOrderByGrantDateAscIdAsc(Long userId);

    boolean existsByUserIdAndGrantDateAndSource(Long userId, LocalDate grantDate, String source);

    boolean existsByUserIdAndSource(Long userId, String source);
}
