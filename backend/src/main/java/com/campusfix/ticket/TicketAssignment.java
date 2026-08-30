package com.campusfix.ticket;

import java.time.LocalDateTime;

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
        name = "ticket_assignments",
        indexes = {
            @Index(name = "idx_ticket_assignments_ticket_active", columnList = "ticket_id, is_active"),
            @Index(name = "idx_ticket_assignments_technician_active", columnList = "technician_id, is_active")
        })
public class TicketAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "technician_id", nullable = false)
    private User technician;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false)
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(length = 1000)
    private String reason;

    protected TicketAssignment() {
    }

    public TicketAssignment(Ticket ticket, User technician, User assignedBy) {
        this.ticket = ticket;
        this.technician = technician;
        this.assignedBy = assignedBy;
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public User getTechnician() {
        return technician;
    }

    public User getAssignedBy() {
        return assignedBy;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public boolean isActive() {
        return active;
    }

    public String getReason() {
        return reason;
    }

    public void end(String reason) {
        this.active = false;
        this.endedAt = LocalDateTime.now();
        this.reason = reason;
    }
}
