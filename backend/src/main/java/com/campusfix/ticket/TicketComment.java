package com.campusfix.ticket;

import java.time.LocalDateTime;

import com.campusfix.user.Role;
import com.campusfix.user.User;
import jakarta.persistence.*;

@Entity
@Table(name = "ticket_comments", indexes = @Index(
        name = "idx_ticket_comments_ticket_time", columnList = "ticket_id, created_at"))
public class TicketComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 30)
    private Role authorRole;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected TicketComment() {}

    public TicketComment(Ticket ticket, User author, Role authorRole, String body) {
        this.ticket = ticket;
        this.author = author;
        this.authorRole = authorRole;
        this.body = body;
    }

    public Long getId() { return id; }
    public User getAuthor() { return author; }
    public Role getAuthorRole() { return authorRole; }
    public String getBody() { return body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
