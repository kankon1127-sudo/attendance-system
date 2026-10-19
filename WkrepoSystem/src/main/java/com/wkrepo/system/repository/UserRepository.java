package com.wkrepo.system.repository;

import java.util.List;

import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    

    List<User> findByDepartment(String department);

    Optional<User> findByUsername(String username);
    
    Optional<User> findByEmployeeId(String employeeId);
    
    boolean existsByUsername(String username);
    
    boolean existsByEmployeeId(String employeeId);
}
