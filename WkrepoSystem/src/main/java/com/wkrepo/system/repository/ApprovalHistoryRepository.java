package com.wkrepo.system.repository;

import com.wkrepo.system.entity.ApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ApprovalHistoryRepository extends JpaRepository<ApprovalHistory, Long> {

    List<ApprovalHistory> findByApplicationTypeAndApplicationIdOrderByActionAtAscIdAsc(
            String applicationType, Long applicationId);

    /** 指定した遷移先の最新の履歴（月報の 1 次承認者の確認用） */
    java.util.Optional<ApprovalHistory> findFirstByApplicationTypeAndApplicationIdAndNextStatusOrderByActionAtDescIdDesc(
            String applicationType, Long applicationId, String nextStatus);

    /** 申請一覧の拒否理由の表示用：複数の申請の、指定した遷移先の履歴を古い順に */
    List<ApprovalHistory> findByApplicationTypeAndApplicationIdInAndNextStatusOrderByActionAtAscIdAsc(
            String applicationType, Collection<Long> applicationIds, String nextStatus);
}
