package com.campusfix.user;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, Long> {

    long countBySystemRoleCodeAndActiveTrue(Role role);
}
