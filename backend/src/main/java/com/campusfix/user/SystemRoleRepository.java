package com.campusfix.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface SystemRoleRepository extends JpaRepository<SystemRole, Long> {

    Optional<SystemRole> findByCode(Role code);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select role from SystemRole role where role.code = :code")
    Optional<SystemRole> lockByCode(@Param("code") Role code);
}
