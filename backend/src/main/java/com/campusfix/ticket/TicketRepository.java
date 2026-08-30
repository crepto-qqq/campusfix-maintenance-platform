package com.campusfix.ticket;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
