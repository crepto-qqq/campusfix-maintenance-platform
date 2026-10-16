package com.campusfix.review;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewAuditRepository extends JpaRepository<ReviewAudit, Long> {
    @EntityGraph(attributePaths = {"actor", "actor.profile"})
    List<ReviewAudit> findByReview_IdOrderByOccurredAtAscIdAsc(Long reviewId);
}
