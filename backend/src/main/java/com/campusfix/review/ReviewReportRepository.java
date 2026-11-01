package com.campusfix.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {
    @EntityGraph(attributePaths = {"review", "review.ticket", "review.author", "reporter", "reporter.profile"})
    List<ReviewReport> findAllByOrderByCreatedAtDescIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from ReviewReport report where report.id = :id")
    Optional<ReviewReport> findByIdForUpdate(@Param("id") Long id);

    boolean existsByPendingKey(String pendingKey);

    boolean existsByReview_IdAndReporter_Id(Long reviewId, Long reporterId);

    @EntityGraph(attributePaths = {"reporter", "review", "review.ticket"})
    List<ReviewReport> findByReview_IdAndStatus(Long reviewId, ReviewReportStatus status);
}
