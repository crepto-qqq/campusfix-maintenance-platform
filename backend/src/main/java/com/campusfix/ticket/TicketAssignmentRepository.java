package com.campusfix.ticket;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketAssignmentRepository extends JpaRepository<TicketAssignment, Long> {

    @EntityGraph(attributePaths = {"ticket", "technician", "technician.profile", "assignedBy", "assignedBy.profile"})
    Optional<TicketAssignment> findByTicket_IdAndActiveTrue(Long ticketId);

    long countByTicket_Id(Long ticketId);
}
