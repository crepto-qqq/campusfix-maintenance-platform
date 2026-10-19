package com.campusfix.notification;

import java.time.LocalDateTime;
import java.util.List;

import com.campusfix.user.AccessControlService;
import com.campusfix.review.ReviewService;
import com.campusfix.user.Role;
import com.campusfix.user.User;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/notifications")
@CrossOrigin(
        origins = {"http://localhost:5500", "http://127.0.0.1:5500"},
        allowCredentials = "true"
)
public class NotificationController {

    private final InAppNotificationRepository notificationRepository;
    private final AccessControlService accessControlService;
    private final ReviewService reviewService;

    public NotificationController(
            InAppNotificationRepository notificationRepository,
            AccessControlService accessControlService,
            ReviewService reviewService) {
        this.notificationRepository = notificationRepository;
        this.accessControlService = accessControlService;
        this.reviewService = reviewService;
    }

    @GetMapping
    @Transactional
    public InboxResponse getInbox(HttpSession session) {
        User user = accessControlService.requireAuthenticatedUser(session);
        Role activeRole = accessControlService.requireActiveRole(user);
        if (activeRole == Role.GENERAL_USER) {
            reviewService.ownBan(user);
        }
        List<NotificationResponse> notifications = notificationRepository
                .findByRecipient_IdOrderByCreatedAtDescIdDesc(user.getId()).stream()
                .filter(notification -> visibleTo(notification, activeRole, user))
                .map(NotificationController::toResponse)
                .toList();
        long unreadCount = notifications.stream()
                .filter(notification -> notification.readAt() == null)
                .count();
        return new InboxResponse(notifications, unreadCount);
    }

    @PutMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markRead(@PathVariable Long notificationId, HttpSession session) {
        User user = accessControlService.requireAuthenticatedUser(session);
        Role activeRole = accessControlService.requireActiveRole(user);
        InAppNotification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        if (!notification.getRecipient().getId().equals(user.getId())
                || !visibleTo(notification, activeRole, user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        notification.markRead();
    }

    private static NotificationResponse toResponse(InAppNotification notification) {
        boolean reporterNotice = notification.getType() == NotificationType.REVIEW_REPORT_SUBMITTED
                || notification.getType() == NotificationType.REVIEW_REPORT_RESOLVED;
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getMessage(),
                reporterNotice || notification.getTicket() == null ? null : notification.getTicket().getId(),
                notification.getCreatedAt(),
                notification.getReadAt());
    }

    /** Keep notifications from another workspace role out of this tab's inbox. */
    private static boolean visibleTo(InAppNotification notification, Role activeRole, User recipient) {
        NotificationType type = notification.getType();
        return switch (activeRole) {
            case GENERAL_USER -> switch (type) {
                case STATUS_CHANGED, TICKET_COMPLETED,
                        RESOLUTION_CONFIRMED, TICKET_REOPENED,
                        REVIEW_TEXT_REMOVED, REVIEW_REMOVED, REVIEW_RESTORED,
                        REVIEW_REPORT_SUBMITTED, REVIEW_REPORT_RESOLVED,
                        REVIEW_BANNED, REVIEW_UNBANNED -> true;
                case TICKET_ASSIGNED -> notification.getDeduplicationKey().startsWith("TICKET_ASSIGNED_REQUESTER:")
                        && notification.getTicket() != null
                        && notification.getTicket().getSubmittedBy().getId().equals(recipient.getId());
                case COMMENT_ADDED, TICKET_REASSIGNED -> notification.getTicket() != null
                        && notification.getTicket().getSubmittedBy().getId().equals(recipient.getId());
                default -> false;
            };
            case MAINTENANCE_STAFF -> type == NotificationType.TICKET_REASSIGNED
                    || (type == NotificationType.TICKET_ASSIGNED
                    && !notification.getDeduplicationKey().startsWith("TICKET_ASSIGNED_REQUESTER:"))
                    || (type == NotificationType.COMMENT_ADDED
                    && notification.getTicket() != null
                    && notification.getTicket().getActiveAssignment() != null
                    && notification.getTicket().getActiveAssignment().getTechnician().getId().equals(recipient.getId()));
            case ADMINISTRATOR -> type == NotificationType.REVIEW_REPORT_RECEIVED;
            case MANAGER -> false;
        };
    }

    public record NotificationResponse(
            Long id,
            NotificationType type,
            String message,
            Long ticketId,
            LocalDateTime createdAt,
            LocalDateTime readAt) {
    }

    public record InboxResponse(List<NotificationResponse> notifications, long unreadCount) {
    }
}
