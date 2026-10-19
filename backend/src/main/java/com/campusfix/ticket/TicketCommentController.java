package com.campusfix.ticket;

import java.time.LocalDateTime;
import java.util.List;

import com.campusfix.notification.InAppNotification;
import com.campusfix.notification.InAppNotificationRepository;
import com.campusfix.notification.NotificationType;
import com.campusfix.user.AccessControlService;
import com.campusfix.user.Role;
import com.campusfix.user.User;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tickets/{ticketId}/comments")
@CrossOrigin(origins = {"http://localhost:5500", "http://127.0.0.1:5500"}, allowCredentials = "true")
public class TicketCommentController {
    private final AccessControlService access;
    private final TicketService tickets;
    private final TicketRepository ticketRepository;
    private final TicketCommentRepository comments;
    private final InAppNotificationRepository notifications;

    public TicketCommentController(AccessControlService access, TicketService tickets,
            TicketRepository ticketRepository, TicketCommentRepository comments,
            InAppNotificationRepository notifications) {
        this.access = access;
        this.tickets = tickets;
        this.ticketRepository = ticketRepository;
        this.comments = comments;
        this.notifications = notifications;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CommentResponse> list(@PathVariable Long ticketId, HttpSession session) {
        User viewer = access.requireAuthenticatedUser(session);
        tickets.getTicketForViewer(ticketId, viewer, access.requireActiveRole(viewer));
        return comments.findByTicket_IdOrderByCreatedAtAscIdAsc(ticketId).stream()
                .map(TicketCommentController::response).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CommentResponse add(@PathVariable Long ticketId, @Valid @RequestBody CommentRequest request,
            HttpSession session) {
        User author = access.requireRole(session, Role.GENERAL_USER, Role.MAINTENANCE_STAFF);
        Role role = access.requireActiveRole(author);
        // Serialize with assignment/reassignment so an old technician cannot post after losing access.
        ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
        Ticket ticket = tickets.getTicketForViewer(ticketId, author, role);
        if (List.of(TicketStatus.COMPLETED, TicketStatus.CLOSED, TicketStatus.CANCELLED).contains(ticket.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Comments are read-only after a request ends");
        }
        TicketComment comment = comments.saveAndFlush(new TicketComment(ticket, author, role, request.body().strip()));
        TicketAssignment assignment = ticket.getActiveAssignment();
        User recipient = role == Role.MAINTENANCE_STAFF ? ticket.getSubmittedBy()
                : assignment == null ? null : assignment.getTechnician();
        if (recipient != null && !recipient.getId().equals(author.getId())) {
            notifications.save(new InAppNotification(recipient, author, ticket, NotificationType.COMMENT_ADDED,
                    "New message on maintenance request #" + ticket.getId() + ": " + ticket.getTitle(),
                    "COMMENT_ADDED:" + comment.getId() + ":" + recipient.getId()));
        }
        return response(comment);
    }

    private static CommentResponse response(TicketComment comment) {
        return new CommentResponse(comment.getId(), comment.getAuthor().getName(), comment.getAuthorRole(),
                comment.getBody(), comment.getCreatedAt());
    }

    public record CommentRequest(@NotBlank @Size(max = 2000) String body) {}
    public record CommentResponse(Long id, String authorName, Role authorRole, String body, LocalDateTime createdAt) {}
}
