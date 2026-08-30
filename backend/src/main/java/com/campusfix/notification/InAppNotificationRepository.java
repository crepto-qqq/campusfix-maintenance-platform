package com.campusfix.notification;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InAppNotificationRepository extends JpaRepository<InAppNotification, Long> {

    @EntityGraph(attributePaths = {"recipient", "recipient.profile", "actor", "actor.profile", "ticket"})
    List<InAppNotification> findByRecipient_IdOrderByCreatedAtDescIdDesc(Long recipientId);

    long countByTicket_IdAndType(Long ticketId, NotificationType type);
}
