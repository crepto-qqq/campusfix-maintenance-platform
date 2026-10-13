package com.campusfix.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TechnicianReviewRepository extends JpaRepository<TechnicianReview, Long> {
    @EntityGraph(attributePaths = {"ticket", "technician", "technician.profile", "author", "author.profile"})
    Optional<TechnicianReview> findByTicket_Id(Long ticketId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select review from TechnicianReview review where review.ticket.id = :ticketId")
    Optional<TechnicianReview> findByTicketIdForUpdate(@Param("ticketId") Long ticketId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select review from TechnicianReview review where review.id = :id")
    Optional<TechnicianReview> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {"ticket", "technician", "technician.profile", "author", "author.profile"})
    List<TechnicianReview> findAllByOrderByCreatedAtDescIdDesc();

    @EntityGraph(attributePaths = {"technician", "technician.profile"})
    List<TechnicianReview> findByVisibilityNotOrderByCreatedAtDescIdDesc(ReviewVisibility visibility);

    @EntityGraph(attributePaths = {"technician", "technician.profile"})
    List<TechnicianReview> findByTechnician_IdAndVisibilityNotOrderByCreatedAtDescIdDesc(
            Long technicianId, ReviewVisibility visibility);

}
