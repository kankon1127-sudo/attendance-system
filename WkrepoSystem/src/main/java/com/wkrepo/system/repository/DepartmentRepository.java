package com.wkrepo.system.repository;

import com.wkrepo.system.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    List<Department> findAllByOrderBySortOrderAscNameAsc();

    List<Department> findByActiveTrueOrderBySortOrderAscNameAsc();

    Optional<Department> findByName(String name);

    boolean existsByName(String name);
}
