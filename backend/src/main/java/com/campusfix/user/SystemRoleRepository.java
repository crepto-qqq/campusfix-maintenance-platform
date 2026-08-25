package com.campusfix.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemRoleRepository extends JpaRepository<SystemRole, Long> {

    Optional<SystemRole> findByCode(Role code);
}
