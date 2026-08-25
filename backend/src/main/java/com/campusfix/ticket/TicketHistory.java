package com.campusfix.ticket;

import java.time.LocalDateTime;

import com.campusfix.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "ticket_history",
        indexes = @Index(name = "idx_ticket_history_ticket_time", columnList = "ticket_id, occurred_at")
)
public class TicketHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private TicketEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Lob
    @Column(name = "previous_value")
    private String previousValue;

    @Lob
    @Column(name = "new_value")
    private String newValue;

    @Column(length = 1000)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    protected TicketHistory() {
    }

    public TicketHistory(
            Ticket ticket,
            TicketEventType eventType,
            User actor,
            String previousValue,
            String newValue,
            String reason) {
        this.ticket = ticket;
        this.eventType = eventType;
        this.actor = actor;
        this.previousValue = previousValue;
        this.newValue = newValue;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public TicketEventType getEventType() {
        return eventType;
    }

    public User getActor() {
        return actor;
    }

    public String getPreviousValue() {
        return previousValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
