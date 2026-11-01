package com.campusfix.review;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReviewBanRepository extends JpaRepository<ReviewBan, Long> {
    @EntityGraph(attributePaths = {"administrator", "student"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ban from ReviewBan ban where ban.student.id = :studentId "
            + "and ban.active = true order by ban.createdAt desc")
    List<ReviewBan> findActiveForUpdate(@Param("studentId") Long studentId);
}
