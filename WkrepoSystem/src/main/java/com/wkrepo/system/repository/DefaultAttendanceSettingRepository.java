package com.wkrepo.system.repository;

import com.wkrepo.system.entity.DefaultAttendanceSetting;
import com.wkrepo.system.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DefaultAttendanceSettingRepository extends JpaRepository<DefaultAttendanceSetting, Long> {
    Optional<DefaultAttendanceSetting> findByUser(User user);
}