package com.campusfix.review;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.notification.InAppNotification;
import com.campusfix.notification.InAppNotificationRepository;
import com.campusfix.notification.NotificationType;
import com.campusfix.ticket.Ticket;
import com.campusfix.ticket.TicketAssignment;
import com.campusfix.ticket.TicketRepository;
import com.campusfix.ticket.TicketStatus;
import com.campusfix.user.AccountStatus;
import com.campusfix.user.Role;
import com.campusfix.user.User;
import com.campusfix.user.UserRepository;

@Service
public class ReviewService {
    private final TechnicianReviewRepository reviews;
    private final ReviewAuditRepository audits;
    private final ReviewReportRepository reports;
    private final ReviewBanRepository bans;
    private final TicketRepository tickets;
    private final UserRepository users;
    private final InAppNotificationRepository notifications;

    public ReviewService(TechnicianReviewRepository reviews, ReviewAuditRepository audits,
            ReviewReportRepository reports, ReviewBanRepository bans, TicketRepository tickets,
            UserRepository users, InAppNotificationRepository notifications) {
        this.reviews = reviews;
        this.audits = audits;
        this.reports = reports;
        this.bans = bans;
        this.tickets = tickets;
        this.users = users;
        this.notifications = notifications;
    }

    @Transactional
    public OwnerReview upsert(Long ticketId, User student, Integer score, String comment) {
        if (score == null || score < 1 || score > 5) {
            throw badRequest("Score must be an integer from 1 to 5");
        }
        String text = normalizedComment(comment);
        if (activeBan(student.getId()) != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your review submission is suspended");
        }

        // Lock the ticket as well as the review so concurrent first submissions cannot create duplicates.
        Ticket ticket = tickets.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        if (!ticket.getSubmittedBy().getId().equals(student.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the requester can review this ticket");
        }
        if (ticket.getStatus() != TicketStatus.COMPLETED && ticket.getStatus() != TicketStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The ticket has not been completed");
        }
        TicketAssignment assignment = ticket.getActiveAssignment();
        if (assignment == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The completed technician is unavailable");
        }
        User technician = assignment.getTechnician();
        if (student.getId().equals(technician.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot review your own maintenance work");
        }

        TechnicianReview review = reviews.findByTicketIdForUpdate(ticketId).orElse(null);
        if (review == null) {
            review = reviews.save(new TechnicianReview(ticket, technician, student, score, text));
            audits.save(new ReviewAudit(review, student, "SUBMITTED", null, null, null, null));
        } else {
            if (!review.getAuthor().getId().equals(student.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot modify this review");
            }
            int oldScore = review.getScore();
            String oldComment = review.getComment();
            ReviewVisibility oldVisibility = review.getVisibility();
            review.revise(score, text);
            reviews.save(review);
            audits.save(new ReviewAudit(review, student, "EDITED", null,
                    oldScore, oldComment, oldVisibility));
        }
        return owner(review);
    }

    @Transactional(readOnly = true)
    public OwnerReview mine(Long ticketId, User student) {
        Ticket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        if (!ticket.getSubmittedBy().getId().equals(student.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the requester can view this review");
        }
        TechnicianReview review = reviews.findByTicket_Id(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
        return owner(review);
    }

    @Transactional(readOnly = true)
    public List<TechnicianSummary> publicCatalog() {
        Map<Long, List<TechnicianReview>> byTechnician = new HashMap<>();
        for (TechnicianReview review : reviews.findByVisibilityNotOrderByCreatedAtDescIdDesc(ReviewVisibility.REMOVED)) {
            byTechnician.computeIfAbsent(review.getTechnician().getId(), key -> new ArrayList<>()).add(review);
        }
        return users.findAllWithAccessOrderByNameAndEmail().stream()
                .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE && user.hasRole(Role.MAINTENANCE_STAFF))
                .map(user -> technicianSummary(user, byTechnician.getOrDefault(user.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TechnicianSummary publicTechnician(Long technicianId) {
        User technician = users.findById(technicianId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found"));
        if (technician.getAccountStatus() != AccountStatus.ACTIVE || !technician.hasRole(Role.MAINTENANCE_STAFF)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Technician not found");
        }
        return technicianSummary(technician, reviews
                .findByTechnician_IdAndVisibilityNotOrderByCreatedAtDescIdDesc(technicianId, ReviewVisibility.REMOVED));
    }

    @Transactional(readOnly = true)
    public RatingSummary ownAverage(User technician) {
        return ratingSummaries().getOrDefault(technician.getId(), new RatingSummary(null, 0, 3.5));
    }

    @Transactional(readOnly = true)
    public Map<Long, RatingSummary> ratingSummaries() {
        Map<Long, int[]> totals = new HashMap<>();
        for (TechnicianReview review : reviews.findByVisibilityNotOrderByCreatedAtDescIdDesc(ReviewVisibility.REMOVED)) {
            int[] values = totals.computeIfAbsent(review.getTechnician().getId(), key -> new int[2]);
            values[0] += review.getScore();
            values[1]++;
        }
        Map<Long, RatingSummary> result = new HashMap<>();
        totals.forEach((id, values) -> result.put(id, new RatingSummary(
                (double) values[0] / values[1], values[1], (values[0] + 3.5 * 5) / (values[1] + 5))));
        return result;
    }

    @Transactional
    public ReportReceipt report(Long reviewId, User reporter, String reason) {
        String normalizedReason = requiredReason(reason);
        TechnicianReview review = reviewForUpdate(reviewId);
        if (review.getVisibility() == ReviewVisibility.REMOVED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found");
        }
        if (review.getAuthor().getId().equals(reporter.getId())) {
            throw badRequest("You cannot report your own review");
        }
        String pendingKey = reviewId + ":" + reporter.getId();
        if (reports.existsByPendingKey(pendingKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your report is already pending");
        }
        ReviewReport report = reports.saveAndFlush(new ReviewReport(review, reporter, normalizedReason));
        notify(reporter, reporter, null, NotificationType.REVIEW_REPORT_SUBMITTED,
                "Your report for review #" + reviewId + " was received",
                "REVIEW_REPORT_SUBMITTED:" + report.getId() + ":" + reporter.getId());
        for (User administrator : users.findAllWithAccessOrderByNameAndEmail()) {
            if (administrator.getAccountStatus() == AccountStatus.ACTIVE
                    && administrator.hasRole(Role.ADMINISTRATOR)
                    && !administrator.getId().equals(reporter.getId())) {
                notify(administrator, reporter, review.getTicket(), NotificationType.REVIEW_REPORT_RECEIVED,
                        "Review #" + reviewId + " was reported for moderation",
                        "REVIEW_REPORT_RECEIVED:" + report.getId() + ":" + administrator.getId());
            }
        }
        return new ReportReceipt(report.getId(), reviewId, report.getStatus(), report.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<AdminReview> adminReviews() {
        return reviews.findAllByOrderByCreatedAtDescIdDesc().stream().map(this::adminView).toList();
    }

    @Transactional(readOnly = true)
    public List<ReportView> adminReports() {
        return reports.findAllByOrderByCreatedAtDescIdDesc().stream().map(this::reportView).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditView> audit(Long reviewId) {
        if (!reviews.existsById(reviewId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found");
        }
        return audits.findByReview_IdOrderByOccurredAtAscIdAsc(reviewId).stream()
                .map(audit -> new AuditView(audit.getId(), audit.getAction(), audit.getReason(),
                        audit.getActor().getId(), audit.getActor().getName(), audit.getBeforeScore(),
                        audit.getAfterScore(), audit.getBeforeComment(), audit.getAfterComment(),
                        audit.getBeforeVisibility(), audit.getAfterVisibility(), audit.getOccurredAt()))
                .toList();
    }

    @Transactional
    public AdminReview moderate(Long reviewId, User administrator, ModerationAction action, String reason) {
        String normalizedReason = requiredReason(reason);
        TechnicianReview review = reviewForUpdate(reviewId);
        preventSelfReviewAction(review, administrator);
        applyModeration(review, administrator, action, normalizedReason);
        if (action != ModerationAction.RESTORE) {
            resolvePendingReports(review, administrator, action.name(), normalizedReason);
        }
        return adminView(review);
    }

    @Transactional
    public ReportView resolveReport(Long reportId, User administrator, ReportAction action,
            String reason, LocalDateTime expiresAt) {
        String normalizedReason = requiredReason(reason);
        ReviewReport existing = reports.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        // Match direct moderation's lock order: review first, then the report.
        TechnicianReview lockedReview = reviewForUpdate(existing.getReview().getId());
        ReviewReport report = reports.findByIdForUpdate(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));
        if (report.getStatus() != ReviewReportStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Report has already been resolved");
        }
        if (report.getReporter().getId().equals(administrator.getId())
                || report.getReview().getAuthor().getId().equals(administrator.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot handle your own report or review");
        }
        if (reports.existsByReview_IdAndReporter_Id(report.getReview().getId(), administrator.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot handle a review you reported");
        }
        if (action == null) {
            throw badRequest("A report action is required");
        }
        if (action == ReportAction.REMOVE_TEXT || action == ReportAction.REMOVE_ALL) {
            applyModeration(lockedReview, administrator,
                    action == ReportAction.REMOVE_TEXT ? ModerationAction.REMOVE_TEXT : ModerationAction.REMOVE_ALL,
                    normalizedReason);
            resolvePendingReports(lockedReview, administrator, action.name(), normalizedReason);
        } else {
            if (action == ReportAction.BAN_AUTHOR) {
                if (lockedReview.getVisibility() == ReviewVisibility.ACTIVE
                        && lockedReview.getComment() != null
                        && lockedReview.getComment().equals(report.getReportedComment())) {
                    applyModeration(lockedReview, administrator, ModerationAction.REMOVE_TEXT, normalizedReason);
                }
                banInternal(lockedReview.getAuthor(), administrator, normalizedReason, expiresAt);
                resolvePendingReports(lockedReview, administrator, action.name(), normalizedReason);
            } else {
                resolveOneReport(report, administrator, action.name(), normalizedReason,
                        ReviewReportStatus.DISMISSED);
            }
        }
        return reportView(report);
    }

    @Transactional
    public BanView ownBan(User student) {
        return banView(activeBan(student.getId()));
    }

    @Transactional
    public BanView adminBan(Long studentId) {
        requireStudent(studentId);
        return banView(activeBan(studentId));
    }

    @Transactional
    public BanView ban(Long studentId, User administrator, String reason, LocalDateTime expiresAt) {
        User student = requireStudent(studentId);
        preventSelfBan(student, administrator);
        return banView(banInternal(student, administrator, requiredReason(reason), expiresAt));
    }

    @Transactional
    public BanView unban(Long studentId, User administrator, String reason) {
        User student = lockedStudent(studentId);
        preventSelfBan(student, administrator);
        ReviewBan current = activeBan(studentId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Student is not currently banned");
        }
        current.end(administrator, requiredReason(reason));
        bans.save(current);
        notify(student, administrator, null, NotificationType.REVIEW_UNBANNED,
                "Your ability to submit reviews was restored. Reason: " + current.getEndReason(),
                "REVIEW_UNBANNED:" + current.getId());
        return banView(null);
    }

    private ReviewBan banInternal(User student, User administrator, String reason, LocalDateTime expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw badRequest("Ban expiry must be in the future");
        }
        User lockedStudent = lockedStudent(student.getId());
        if (activeBan(lockedStudent.getId()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Student is already banned from reviews");
        }
        ReviewBan ban = bans.save(new ReviewBan(lockedStudent, administrator, reason, expiresAt));
        notify(lockedStudent, administrator, null, NotificationType.REVIEW_BANNED,
                "Your ability to submit reviews is suspended: " + reason
                        + (expiresAt == null ? " (permanent)" : " (until " + expiresAt + ")"),
                "REVIEW_BANNED:" + ban.getId());
        return ban;
    }

    private ReviewBan activeBan(Long studentId) {
        for (ReviewBan ban : bans.findActiveForUpdate(studentId)) {
            if (ban.isCurrentlyBanned()) {
                return ban;
            }
            ban.end(null, "Ban expired automatically");
            bans.save(ban);
            notify(ban.getStudent(), ban.getAdministrator(), null, NotificationType.REVIEW_UNBANNED,
                    "Your review submission suspension expired automatically",
                    "REVIEW_UNBANNED:" + ban.getId());
        }
        return null;
    }

    private void applyModeration(TechnicianReview review, User administrator,
            ModerationAction action, String reason) {
        if (action == null) {
            throw badRequest("A moderation action is required");
        }
        ReviewVisibility next = switch (action) {
            case REMOVE_TEXT -> ReviewVisibility.TEXT_REMOVED;
            case REMOVE_ALL -> ReviewVisibility.REMOVED;
            case RESTORE -> ReviewVisibility.ACTIVE;
        };
        if (review.getVisibility() == next) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Review already has this state");
        }
        if (review.getVisibility() == ReviewVisibility.REMOVED && action == ModerationAction.REMOVE_TEXT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Restore the removed review before changing its text");
        }
        if (action == ModerationAction.REMOVE_TEXT && review.getComment() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Review has no text to remove");
        }
        ReviewVisibility before = review.getVisibility();
        int score = review.getScore();
        String comment = review.getComment();
        review.setVisibility(next);
        reviews.save(review);
        ReviewAudit audit = audits.saveAndFlush(
                new ReviewAudit(review, administrator, action.name(), reason, score, comment, before));
        NotificationType type = switch (action) {
            case REMOVE_TEXT -> NotificationType.REVIEW_TEXT_REMOVED;
            case REMOVE_ALL -> NotificationType.REVIEW_REMOVED;
            case RESTORE -> NotificationType.REVIEW_RESTORED;
        };
        String outcome = switch (action) {
            case REMOVE_TEXT -> "The text was removed; the star rating remains.";
            case REMOVE_ALL -> "The star rating and text were removed.";
            case RESTORE -> before == ReviewVisibility.TEXT_REMOVED
                    ? "The text was restored; the star rating remained active."
                    : "The star rating and text were restored.";
        };
        notify(review.getAuthor(), administrator, review.getTicket(), type,
                "Review for maintenance request #" + review.getTicket().getId()
                        + ": " + outcome + " Reason: " + reason,
                type.name() + ":" + audit.getId());
    }

    private void resolvePendingReports(TechnicianReview review, User administrator,
            String action, String reason) {
        for (ReviewReport report : reports.findByReview_IdAndStatus(review.getId(), ReviewReportStatus.PENDING)) {
            resolveOneReport(report, administrator, action, reason, ReviewReportStatus.ACTIONED);
        }
    }

    private void resolveOneReport(ReviewReport report, User administrator, String action,
            String reason, ReviewReportStatus status) {
        report.resolve(status, action, reason, administrator);
        reports.save(report);
        notify(report.getReporter(), administrator, null,
                NotificationType.REVIEW_REPORT_RESOLVED,
                "Your report for review #" + report.getReview().getId() + " was "
                        + (status == ReviewReportStatus.DISMISSED ? "dismissed" : "handled") + ".",
                "REVIEW_REPORT_RESOLVED:" + report.getId());
    }

    private void notify(User recipient, User actor, Ticket ticket, NotificationType type,
            String message, String deduplicationKey) {
        notifications.save(new InAppNotification(recipient, actor, ticket, type,
                message.length() > 500 ? message.substring(0, 500) : message, deduplicationKey));
    }

    private User requireStudent(Long studentId) {
        User student = users.findById(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        if (!student.hasRole(Role.GENERAL_USER)) {
            throw badRequest("The selected account has no student workspace role");
        }
        return student;
    }

    private User lockedStudent(Long studentId) {
        User student = users.findByIdForReviewBanUpdate(studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Student not found"));
        if (!student.hasRole(Role.GENERAL_USER)) {
            throw badRequest("The selected account has no student workspace role");
        }
        return student;
    }

    private void preventSelfBan(User student, User administrator) {
        if (student.getId().equals(administrator.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot change your own review ban");
        }
    }

    private void preventSelfReviewAction(TechnicianReview review, User administrator) {
        if (review.getAuthor().getId().equals(administrator.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot moderate your own review");
        }
        if (reports.existsByReview_IdAndReporter_Id(review.getId(), administrator.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot handle a review you reported");
        }
    }

    private TechnicianReview reviewForUpdate(Long reviewId) {
        return reviews.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found"));
    }

    private String normalizedComment(String comment) {
        String value = comment == null ? null : comment.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > 1000) {
            throw badRequest("Comment must not exceed 1000 characters");
        }
        return value;
    }

    private String requiredReason(String reason) {
        String value = reason == null ? null : reason.trim();
        if (value == null || value.isEmpty() || value.length() > 1000) {
            throw badRequest("A reason from 1 to 1000 characters is required");
        }
        return value;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private TechnicianSummary technicianSummary(User technician, List<TechnicianReview> technicianReviews) {
        int total = technicianReviews.stream().mapToInt(TechnicianReview::getScore).sum();
        Double average = technicianReviews.isEmpty() ? null : (double) total / technicianReviews.size();
        return new TechnicianSummary(technician.getId(), technician.getName(), average,
                technicianReviews.size(), technicianReviews.stream().map(this::publicView).toList());
    }

    private PublicReview publicView(TechnicianReview review) {
        return new PublicReview(review.getId(), review.getScore(),
                review.getVisibility() == ReviewVisibility.ACTIVE ? review.getComment() : null,
                review.getCreatedAt(), review.getUpdatedAt());
    }

    private OwnerReview owner(TechnicianReview review) {
        return new OwnerReview(review.getId(), review.getTicket().getId(),
                review.getTechnician().getId(), review.getTechnician().getName(), review.getScore(),
                review.getVisibility() == ReviewVisibility.ACTIVE ? review.getComment() : null,
                review.getVisibility(), review.getCreatedAt(), review.getUpdatedAt());
    }

    private AdminReview adminView(TechnicianReview review) {
        return new AdminReview(review.getId(), review.getTicket().getId(),
                review.getTechnician().getId(), review.getTechnician().getName(),
                review.getAuthor().getId(), review.getAuthor().getName(), review.getAuthor().getEmail(),
                review.getScore(), review.getComment(), review.getVisibility(),
                review.getCreatedAt(), review.getUpdatedAt());
    }

    private ReportView reportView(ReviewReport report) {
        return new ReportView(report.getId(), report.getReview().getId(),
                report.getReview().getAuthor().getId(), report.getReporter().getId(),
                report.getReporter().getName(), report.getReportedScore(),
                report.getReportedComment(), report.getReason(), report.getStatus(),
                report.getResolutionAction(), report.getResolutionReason(), report.getCreatedAt(),
                report.getResolvedAt());
    }

    private BanView banView(ReviewBan ban) {
        return ban == null ? new BanView(false, null, null) :
                new BanView(true, ban.getExpiresAt(), ban.getReason());
    }

    public enum ModerationAction { REMOVE_TEXT, REMOVE_ALL, RESTORE }
    public enum ReportAction { DISMISS, REMOVE_TEXT, REMOVE_ALL, BAN_AUTHOR }

    public record RatingSummary(Double averageRating, long ratingCount, double rankingScore) {}
    public record PublicReview(Long id, int score, String comment, LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
    public record TechnicianSummary(Long id, String name, Double averageRating, long ratingCount,
            List<PublicReview> reviews) {}
    public record OwnerReview(Long id, Long ticketId, Long technicianId, String technicianName,
            int score, String comment, ReviewVisibility visibility, LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
    public record AdminReview(Long id, Long ticketId, Long technicianId, String technicianName,
            Long authorId, String authorName, String authorEmail, int score, String comment,
            ReviewVisibility visibility, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record ReportView(Long id, Long reviewId, Long authorId, Long reporterId, String reporterName,
            Integer reportedScore, String reportedComment, String reason, ReviewReportStatus status,
            String resolutionAction, String resolutionReason,
            LocalDateTime createdAt, LocalDateTime resolvedAt) {}
    public record ReportReceipt(Long id, Long reviewId, ReviewReportStatus status,
            LocalDateTime createdAt) {}
    public record AuditView(Long id, String action, String reason, Long actorId, String actorName,
            Integer beforeScore, Integer afterScore, String beforeComment, String afterComment,
            String beforeVisibility, String afterVisibility, LocalDateTime occurredAt) {}
    public record BanView(boolean banned, LocalDateTime expiresAt, String reason) {}
}
