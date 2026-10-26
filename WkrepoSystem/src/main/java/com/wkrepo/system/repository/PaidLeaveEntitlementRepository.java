package com.wkrepo.system.repository;

import com.wkrepo.system.entity.PaidLeaveEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaidLeaveEntitlementRepository extends JpaRepository<PaidLeaveEntitlement, Long> {

    Optional<PaidLeaveEntitlement> findByUserIdAndYear(Long userId, int year);

    List<PaidLeaveEntitlement> findByYear(int year);
}
