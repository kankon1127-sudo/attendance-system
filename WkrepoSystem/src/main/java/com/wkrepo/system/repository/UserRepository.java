package com.wkrepo.system.repository;

import java.util.Collection;
import java.util.List;

import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {


    List<User> findByDepartment(String department);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmployeeId(String employeeId);

    boolean existsByUsername(String username);

    boolean existsByEmployeeId(String employeeId);

    boolean existsByRole(String role);

    long countByDepartment(String department);

    /** 部署マスタの初期登録用：ユーザーが使っている部署名 */
    @Query("SELECT DISTINCT u.department FROM User u WHERE u.department IS NOT NULL AND u.department <> ''")
    List<String> findDistinctDepartments();

    /** 部署名の変更を所属ユーザーに反映する */
    @Modifying
    @Query("UPDATE User u SET u.department = :newName WHERE u.department = :oldName")
    int renameDepartment(@Param("oldName") String oldName, @Param("newName") String newName);

    /** 承認経路：指定した上司（複数可）の直属の部下 */
    List<User> findByManagerIdIn(Collection<Long> managerIds);

    /** 代理承認：この人を代理承認者に指定している上司 */
    List<User> findByApprovalDelegateId(Long delegateId);
}
