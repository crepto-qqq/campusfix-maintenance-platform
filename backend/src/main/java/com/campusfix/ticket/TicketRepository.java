package com.campusfix.ticket;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @Override
    @EntityGraph(attributePaths = {
        "submittedBy",
        "submittedBy.profile",
        "images",
        "assignments",
        "assignments.technician",
        "assignments.technician.profile"
    })
    Optional<Ticket> findById(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {
        "submittedBy",
        "submittedBy.profile",
        "images",
        "assignments",
        "assignments.technician",
        "assignments.technician.profile"
    })
    @Query("select distinct ticket from Ticket ticket where ticket.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = {
        "submittedBy",
        "submittedBy.profile",
        "images",
        "assignments",
        "assignments.technician",
        "assignments.technician.profile"
    })
    List<Ticket> findBySubmittedByIdOrderByCreatedAtDesc(Long submittedById);

    @EntityGraph(attributePaths = {
        "submittedBy",
        "submittedBy.profile",
        "images",
        "assignments",
        "assignments.technician",
        "assignments.technician.profile"
    })
    List<Ticket> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {
        "submittedBy",
        "submittedBy.profile",
        "images",
        "assignments",
        "assignments.technician",
        "assignments.technician.profile"
    })
    List<Ticket> findDistinctByAssignments_Technician_IdAndAssignments_ActiveTrueOrderByCreatedAtDesc(
            Long technicianId);

    long countByOperationalPriority(TicketPriority operationalPriority);

    long countByOperationalPriorityIsNull();

    @Query("select t.status, count(t) from Ticket t group by t.status order by t.status")
    List<Object[]> countsByStatus();

    @Query("select t.category, count(t) from Ticket t group by t.category order by count(t) desc, t.category")
    List<Object[]> countsByCategory();

    @Query("select t.location, count(t) from Ticket t group by t.location order by count(t) desc, t.location")
    List<Object[]> countsByLocation();
}
