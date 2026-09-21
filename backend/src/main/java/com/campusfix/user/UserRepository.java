package com.campusfix.user;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    @EntityGraph(attributePaths = {
        "profile",
        "campusIdentity",
        "roleAssignments",
        "roleAssignments.systemRole"
    })
    @Query("select distinct user from User user order by user.profile.name asc, user.email asc")
    List<User> findAllWithAccessOrderByNameAndEmail();
}
