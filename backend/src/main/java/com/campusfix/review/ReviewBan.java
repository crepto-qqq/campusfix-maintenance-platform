package com.campusfix.review;

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
@Table(name = "review_bans", indexes = @Index(name = "idx_review_bans_student_active", columnList = "student_id, active"))
public class ReviewBan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "administrator_id", nullable = false)
    private User administrator;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "end_reason", length = 1000)
    private String endReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ended_by")
    private User endedBy;

    protected ReviewBan() {}

    public ReviewBan(User student, User administrator, String reason, LocalDateTime expiresAt) {
        this.student = student;
        this.administrator = administrator;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public User getStudent() { return student; }
    public User getAdministrator() { return administrator; }
    public String getReason() { return reason; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public String getEndReason() { return endReason; }
    public User getEndedBy() { return endedBy; }
    public boolean isCurrentlyBanned() {
        return active && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }

    public void end(User actor, String reason) {
        active = false;
        endedAt = LocalDateTime.now();
        endedBy = actor;
        endReason = reason;
    }
}
