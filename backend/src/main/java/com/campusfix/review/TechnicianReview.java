package com.campusfix.review;

import java.time.LocalDateTime;

import com.campusfix.ticket.Ticket;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "technician_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uq_review_ticket", columnNames = "ticket_id"),
        indexes = @Index(name = "idx_review_technician_visibility", columnList = "technician_id, visibility"))
public class TechnicianReview {
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
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false)
    private int score;

    // Retain moderated text privately so an administrator can reverse a mistaken removal.
    @Column(length = 1000)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ReviewVisibility visibility = ReviewVisibility.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected TechnicianReview() {}

    public TechnicianReview(Ticket ticket, User technician, User author, int score, String comment) {
        this.ticket = ticket;
        this.technician = technician;
        this.author = author;
        this.score = score;
        this.comment = comment;
    }

    public Long getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public User getTechnician() { return technician; }
    public User getAuthor() { return author; }
    public int getScore() { return score; }
    public String getComment() { return comment; }
    public ReviewVisibility getVisibility() { return visibility; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void revise(int score, String comment) {
        this.score = score;
        this.comment = comment;
        // A removed review may be resubmitted, but edited text stays hidden until an administrator restores it.
        if (this.visibility == ReviewVisibility.REMOVED) {
            this.visibility = ReviewVisibility.ACTIVE;
        }
        this.updatedAt = LocalDateTime.now();
    }

    public void setVisibility(ReviewVisibility visibility) {
        this.visibility = visibility;
        this.updatedAt = LocalDateTime.now();
    }
}
