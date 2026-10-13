package com.campusfix.review;

import java.time.LocalDateTime;

import com.campusfix.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_audit")
public class ReviewAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private TechnicianReview review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(length = 1000)
    private String reason;

    @Column(name = "before_score")
    private Integer beforeScore;

    @Column(name = "after_score")
    private Integer afterScore;

    @Column(name = "before_comment", length = 1000)
    private String beforeComment;

    @Column(name = "after_comment", length = 1000)
    private String afterComment;

    @Column(name = "before_visibility", length = 24)
    private String beforeVisibility;

    @Column(name = "after_visibility", length = 24)
    private String afterVisibility;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    protected ReviewAudit() {}

    public ReviewAudit(TechnicianReview review, User actor, String action, String reason,
            Integer beforeScore, String beforeComment, ReviewVisibility beforeVisibility) {
        this.review = review;
        this.actor = actor;
        this.action = action;
        this.reason = reason;
        this.beforeScore = beforeScore;
        this.beforeComment = beforeComment;
        this.beforeVisibility = beforeVisibility == null ? null : beforeVisibility.name();
        this.afterScore = review.getScore();
        this.afterComment = review.getComment();
        this.afterVisibility = review.getVisibility().name();
    }

    public Long getId() { return id; }
    public TechnicianReview getReview() { return review; }
    public User getActor() { return actor; }
    public String getAction() { return action; }
    public String getReason() { return reason; }
    public Integer getBeforeScore() { return beforeScore; }
    public Integer getAfterScore() { return afterScore; }
    public String getBeforeComment() { return beforeComment; }
    public String getAfterComment() { return afterComment; }
    public String getBeforeVisibility() { return beforeVisibility; }
    public String getAfterVisibility() { return afterVisibility; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}
