package com.campusfix.ticket;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketCommentRepository extends JpaRepository<TicketComment, Long> {
    @EntityGraph(attributePaths = {"author", "author.profile"})
    List<TicketComment> findByTicket_IdOrderByCreatedAtAscIdAsc(Long ticketId);
}
