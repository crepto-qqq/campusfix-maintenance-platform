package com.campusfix.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface TicketHistoryRepository extends JpaRepository<TicketHistory, Long> {

    @EntityGraph(attributePaths = {"actor", "actor.profile"})
    List<TicketHistory> findByTicket_IdOrderByOccurredAtAscIdAsc(Long ticketId);

    boolean existsByTicket_Id(Long ticketId);
}
