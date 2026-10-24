package com.campusfix.notification;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, Long> {

    @EntityGraph(attributePaths = {"recipient", "recipient.profile", "actor", "actor.profile", "ticket"})
    List<InAppNotification> findByRecipient_IdOrderByCreatedAtDescIdDesc(Long recipientId);

    @Query("select count(n) from InAppNotification n where n.ticket.id = :ticketId and n.type = :type")
    long countByTicketAndTypeCode(@Param("ticketId") Long ticketId, @Param("type") String type);

    default long countByTicket_IdAndType(Long ticketId, NotificationType type) {
        return countByTicketAndTypeCode(ticketId, type.name());
    }
}
