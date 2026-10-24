package com.campusfix.notification;

import java.time.LocalDateTime;

import com.campusfix.ticket.Ticket;
import com.campusfix.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "in_app_notifications",
        indexes = {
            @Index(name = "idx_notifications_recipient_read", columnList = "recipient_id, read_at"),
            @Index(name = "idx_notifications_ticket", columnList = "ticket_id")
        })
public class InAppNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id")
    private Ticket ticket;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "deduplication_key", nullable = false, unique = true, length = 150)
    private String deduplicationKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "read_at")
    private LocalDateTime readAt;

    protected InAppNotification() {
    }

    public InAppNotification(
            User recipient,
            User actor,
            Ticket ticket,
            NotificationType type,
            String message,
            String deduplicationKey) {
        this.recipient = recipient;
        this.actor = actor;
        this.ticket = ticket;
        this.type = type.name();
        this.message = message;
        this.deduplicationKey = deduplicationKey;
    }

    public Long getId() {
        return id;
    }

    public User getRecipient() {
        return recipient;
    }

    public User getActor() {
        return actor;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public NotificationType getType() {
        return NotificationType.valueOf(type);
    }

    public String getMessage() {
        return message;
    }

    public String getDeduplicationKey() {
        return deduplicationKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getReadAt() {
        return readAt;
    }

    public boolean isRead() {
        return readAt != null;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = LocalDateTime.now();
        }
    }
}
