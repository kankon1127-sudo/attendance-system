package com.wkrepo.system.service;

import com.wkrepo.system.entity.User;
import com.wkrepo.system.exception.BusinessException;
import com.wkrepo.system.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 承認経路。
 * <ul>
 *   <li>年休・交通費・諸経費：申請者の直属の上司（users.manager_id）が承認する。上司に代理承認者が設定され、
 *       今日が期間内なら代理承認者も承認できる。上司が未設定の申請者は ADMIN が承認する</li>
 *   <li>月報（1 次承認）：提出時に指定された承認者（＝直属の上司）とその代理承認者</li>
 *   <li>ADMIN はすべて承認できる。誰も自分の申請は承認できない</li>
 * </ul>
 */
@Service
public class ApprovalRouteService {

    private final UserRepository userRepository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalRouteService(UserRepository userRepository) {
        this(userRepository, Clock.systemDefaultZone());
    }

    ApprovalRouteService(UserRepository userRepository, Clock clock) {
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * actor が承認者として行動できる人（本人＋今日代理を頼まれている上司）の ID。
     * 月報の承認者 ID・申請者の manager_id と照合する。
     */
    public Set<Long> actingApproverIds(User actor) {
        Set<Long> ids = new HashSet<>();
        if (actor == null || actor.getId() == null) {
            return ids;
        }
        ids.add(actor.getId());
        if (!canBeApprover(actor)) {
            return ids;
        }
        LocalDate today = LocalDate.now(clock);
        for (User delegator : userRepository.findByApprovalDelegateId(actor.getId())) {
            if (delegator.isApprovalDelegateActiveOn(today) && !"INACTIVE".equals(delegator.getStatus())) {
                ids.add(delegator.getId());
            }
        }
        return ids;
    }

    /** actor が承認できる年休・経費の申請者 ID（直属の部下＋代理中の上司の直属の部下。本人は除く） */
    public Set<Long> approvableApplicantIds(User actor) {
        if (actor == null || actor.getId() == null) {
            return Set.of();
        }
        Set<Long> ids = userRepository.findByManagerIdIn(actingApproverIds(actor)).stream()
                .map(User::getId)
                .collect(Collectors.toCollection(HashSet::new));
        ids.remove(actor.getId());
        return ids;
    }

    /** 年休・経費：actor が applicant の申請を承認できるか（状態のチェックは呼び出し側） */
    public boolean canApproveFor(User actor, User applicant) {
        if (actor == null || applicant == null || actor.getId() == null
                || Objects.equals(actor.getId(), applicant.getId())) {
            return false;
        }
        if ("ADMIN".equals(actor.getRole())) {
            return true;
        }
        return applicant.getManagerId() != null && actingApproverIds(actor).contains(applicant.getManagerId());
    }

    /** 月報の 1 次承認：actor が指定承認者本人か、その代理として承認できるか */
    public boolean canActAsApprover(User actor, Long approverId) {
        if (actor == null || actor.getId() == null) {
            return false;
        }
        if ("ADMIN".equals(actor.getRole())) {
            return true;
        }
        return approverId != null && actingApproverIds(actor).contains(approverId);
    }

    /** 申請時の通知先：直属の上司と、今日有効な代理承認者（重複なし） */
    public List<User> notificationTargets(User applicant) {
        Map<Long, User> targets = new LinkedHashMap<>();
        if (applicant == null || applicant.getManagerId() == null) {
            return List.of();
        }
        userRepository.findById(applicant.getManagerId()).ifPresent(manager -> {
            if (!"INACTIVE".equals(manager.getStatus())) {
                targets.put(manager.getId(), manager);
            }
            if (manager.isApprovalDelegateActiveOn(LocalDate.now(clock))) {
                userRepository.findById(manager.getApprovalDelegateId())
                        .filter(delegate -> !"INACTIVE".equals(delegate.getStatus()))
                        .ifPresent(delegate -> targets.put(delegate.getId(), delegate));
            }
        });
        targets.remove(applicant.getId());
        return new ArrayList<>(targets.values());
    }

    /** 代理承認者の候補：有効な部長・管理者（本人を除く） */
    public List<User> delegateCandidates(User owner) {
        return userRepository.findAll().stream()
                .filter(user -> canBeApprover(user) && !"INACTIVE".equals(user.getStatus())
                        && !Objects.equals(user.getId(), owner.getId()))
                .sorted(java.util.Comparator.comparing(User::getEmployeeId,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /**
     * 代理承認者を設定する（delegateId が null なら解除）。部長・管理者のみ設定でき、代理人も部長・管理者に限る。
     */
    @Transactional
    public User setDelegate(User owner, Long delegateId, LocalDate from, LocalDate to) {
        User managed = userRepository.findById(owner.getId())
                .orElseThrow(() -> new IllegalArgumentException("user.error.notFound"));
        if (!canBeApprover(managed)) {
            throw new SecurityException("delegate.error.notApprover");
        }
        if (delegateId == null) {
            managed.setApprovalDelegateId(null);
            managed.setApprovalDelegateFrom(null);
            managed.setApprovalDelegateTo(null);
            return userRepository.save(managed);
        }
        if (Objects.equals(delegateId, managed.getId())) {
            throw new IllegalArgumentException("delegate.error.self");
        }
        User delegate = userRepository.findById(delegateId)
                .orElseThrow(() -> new IllegalArgumentException("user.error.notFound"));
        if (!canBeApprover(delegate) || "INACTIVE".equals(delegate.getStatus())) {
            throw new BusinessException("delegate.error.invalidDelegate", delegate.getName());
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("delegate.error.invalidPeriod");
        }
        managed.setApprovalDelegateId(delegateId);
        managed.setApprovalDelegateFrom(from);
        managed.setApprovalDelegateTo(to);
        return userRepository.save(managed);
    }

    /** 承認者（上司・代理承認者）になれるロール */
    public static boolean canBeApprover(User user) {
        return user != null && ("MANAGER".equals(user.getRole()) || "ADMIN".equals(user.getRole()));
    }
}
