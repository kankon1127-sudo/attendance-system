package com.wkrepo.system.repository;

import com.wkrepo.system.entity.ApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalHistoryRepository extends JpaRepository<ApprovalHistory, Long> {

    List<ApprovalHistory> findByApplicationTypeAndApplicationIdOrderByActionAtAscIdAsc(
            String applicationType, Long applicationId);
}
