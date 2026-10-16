package com.campusfix.review;

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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_reports", indexes = @Index(name = "idx_review_report_status", columnList = "status, created_at"))
public class ReviewReport {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private TechnicianReview review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Column(nullable = false, length = 1000)
    private String reason;

    // Preserve the public content that was visible when the student reported it.
    @Column(name = "reported_score")
    private Integer reportedScore;

    @Column(name = "reported_comment", length = 1000)
    private String reportedComment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewReportStatus status = ReviewReportStatus.PENDING;

    // Nullable unique key enforces at most one pending report per reporter/review.
    @Column(name = "pending_key", unique = true, length = 100)
    private String pendingKey;

    @Column(name = "resolution_action", length = 30)
    private String resolutionAction;

    @Column(name = "resolution_reason", length = 1000)
    private String resolutionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    protected ReviewReport() {}

    public ReviewReport(TechnicianReview review, User reporter, String reason) {
        this.review = review;
        this.reporter = reporter;
        this.reason = reason;
        this.reportedScore = review.getScore();
        this.reportedComment = review.getVisibility() == ReviewVisibility.ACTIVE
                ? review.getComment() : null;
        this.pendingKey = review.getId() + ":" + reporter.getId();
    }

    public Long getId() { return id; }
    public TechnicianReview getReview() { return review; }
    public User getReporter() { return reporter; }
    public String getReason() { return reason; }
    public Integer getReportedScore() { return reportedScore; }
    public String getReportedComment() { return reportedComment; }
    public ReviewReportStatus getStatus() { return status; }
    public String getResolutionAction() { return resolutionAction; }
    public String getResolutionReason() { return resolutionReason; }
    public User getResolvedBy() { return resolvedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getResolvedAt() { return resolvedAt; }

    public void resolve(ReviewReportStatus status, String action, String reason, User administrator) {
        this.status = status;
        this.resolutionAction = action;
        this.resolutionReason = reason;
        this.resolvedBy = administrator;
        this.resolvedAt = LocalDateTime.now();
        this.pendingKey = null;
    }
}
