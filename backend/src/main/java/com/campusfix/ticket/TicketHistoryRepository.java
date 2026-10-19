package com.campusfix.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {

    @EntityGraph(attributePaths = {"actor", "actor.profile"})
    List<TicketHistory> findByTicket_IdOrderByOccurredAtAscIdAsc(Long ticketId);

    boolean existsByTicket_Id(Long ticketId);

    // The first recorded completion is stable even if comments/evidence later change updated_at.
    @Query("select h.ticket.createdAt, min(h.occurredAt) from TicketHistory h "
            + "where h.eventType = :event and h.newValue = :value and h.ticket.status in :statuses "
            + "group by h.ticket.id, h.ticket.createdAt")
    List<Object[]> completionTimes(@Param("event") TicketEventType event,
            @Param("value") String value, @Param("statuses") List<TicketStatus> statuses);
}
