package com.campusfix.ticket;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketAssignmentRepository extends JpaRepository<TicketAssignment, Long> {

    @EntityGraph(attributePaths = {"ticket", "technician", "technician.profile", "assignedBy", "assignedBy.profile"})
    Optional<TicketAssignment> findByTicket_IdAndActiveTrue(Long ticketId);

    long countByTicket_Id(Long ticketId);

    @Query("select count(assignment) from TicketAssignment assignment "
            + "where assignment.technician.id = :technicianId and assignment.active = true "
            + "and assignment.ticket.status in :statuses")
    long countOpenForTechnician(@Param("technicianId") Long technicianId,
            @Param("statuses") List<TicketStatus> statuses);
}
