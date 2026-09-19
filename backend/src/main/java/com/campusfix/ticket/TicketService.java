package com.campusfix.ticket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.notification.InAppNotification;
import com.campusfix.notification.InAppNotificationRepository;
import com.campusfix.notification.NotificationType;
import com.campusfix.user.AccountStatus;
import com.campusfix.user.User;
import com.campusfix.user.Role;
import com.campusfix.user.UserRepository;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final UserRepository userRepository;
    private final InAppNotificationRepository notificationRepository;

    public TicketService(
            TicketRepository ticketRepository,
            TicketHistoryRepository ticketHistoryRepository,
            UserRepository userRepository,
            InAppNotificationRepository notificationRepository) {
        this.ticketRepository = ticketRepository;
        this.ticketHistoryRepository = ticketHistoryRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public Ticket createTicket(TicketRequest request, User submittedBy) {
        Ticket ticket = new Ticket(
                normalize(request.title()),
                normalize(request.description()),
                request.category(),
                normalize(request.location()),
                request.urgency(),
                submittedBy);
        Ticket saved = ticketRepository.save(ticket);
        recordHistory(
                saved,
                TicketEventType.TICKET_CREATED,
                submittedBy,
                null,
                snapshot(saved),
                null);
        return saved;
    }

    @Transactional(readOnly = true)
    public Ticket getTicketForUser(Long ticketId, Long userId) {
        Ticket ticket = findTicket(ticketId);

        if (!ticket.getSubmittedBy().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Ticket not found");
        }

        return ticket;
    }

    @Transactional(readOnly = true)
    public List<Ticket> getTicketsForUser(Long userId) {
        return ticketRepository.findBySubmittedByIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<Ticket> getTicketsForViewer(User viewer) {
        if (canViewAllTickets(viewer)) {
            return ticketRepository.findAllByOrderByCreatedAtDesc();
        }
        Map<Long, Ticket> visible = new LinkedHashMap<>();
        if (viewer.hasRole(Role.GENERAL_USER)) {
            getTicketsForUser(viewer.getId()).forEach(ticket -> visible.put(ticket.getId(), ticket));
        }
        if (viewer.hasRole(Role.MAINTENANCE_STAFF)) {
            ticketRepository.findDistinctByAssignments_Technician_IdAndAssignments_ActiveTrueOrderByCreatedAtDesc(
                    viewer.getId()).forEach(ticket -> visible.put(ticket.getId(), ticket));
        }
        if (!viewer.hasRole(Role.GENERAL_USER) && !viewer.hasRole(Role.MAINTENANCE_STAFF)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view maintenance requests");
        }
        return visible.values().stream()
                .sorted(Comparator.comparing(Ticket::getCreatedAt).reversed()
                        .thenComparing(Ticket::getId, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Ticket getTicketForViewer(Long ticketId, User viewer) {
        Ticket ticket = findTicket(ticketId);
        boolean isOwner = ticket.getSubmittedBy().getId().equals(viewer.getId());
        boolean isAssignedTechnician = isAssignedTo(ticket, viewer);

        if (!isOwner && !isAssignedTechnician && !canViewAllTickets(viewer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this ticket");
        }

        return ticket;
    }

    @Transactional(readOnly = true)
    public List<TicketHistory> getTicketHistory(Long ticketId, User viewer) {
        Ticket ticket = findTicket(ticketId);
        boolean isOwner = ticket.getSubmittedBy().getId().equals(viewer.getId());
        boolean isAssignedTechnician = isAssignedTo(ticket, viewer);
        boolean canViewAll = canViewAllTickets(viewer);

        if (!isOwner && !isAssignedTechnician && !canViewAll) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this ticket history");
        }

        return ticketHistoryRepository.findByTicket_IdOrderByOccurredAtAscIdAsc(ticketId);
    }

    @Transactional
    public Ticket updateOperationalPriority(
            Long ticketId,
            TicketPriority priority,
            String reason,
            User administrator) {
        if (!administrator.hasRole(Role.ADMINISTRATOR)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only an administrator can change operational priority");
        }

        Ticket ticket = findTicket(ticketId);
        TicketPriority previousPriority = ticket.getOperationalPriority();

        if (previousPriority == priority) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The request already has this operational priority");
        }

        ticket.setOperationalPriority(priority);
        ticket.touch();
        Ticket saved = ticketRepository.save(ticket);
        recordHistory(
                saved,
                TicketEventType.PRIORITY_CHANGED,
                administrator,
                previousPriority == null ? "UNSET" : previousPriority.name(),
                priority.name(),
                normalizeBlankToNull(reason));
        return saved;
    }

    @Transactional(readOnly = true)
    public TicketPrioritySummary getPrioritySummary(User viewer) {
        if (!canViewAllTickets(viewer)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only administrators and managers can view the priority summary");
        }

        return new TicketPrioritySummary(
                ticketRepository.countByOperationalPriorityIsNull(),
                ticketRepository.countByOperationalPriority(TicketPriority.LOW),
                ticketRepository.countByOperationalPriority(TicketPriority.MEDIUM),
                ticketRepository.countByOperationalPriority(TicketPriority.HIGH),
                ticketRepository.countByOperationalPriority(TicketPriority.CRITICAL));
    }

    @Transactional(readOnly = true)
    public List<User> getAssignableTechnicians(User administrator) {
        requireAdministrator(administrator);
        return userRepository.findAllWithAccessOrderByNameAndEmail().stream()
                .filter(this::isAssignableTechnician)
                .toList();
    }

    @Transactional
    public Ticket assignTicket(Long ticketId, Long technicianId, User administrator) {
        requireAdministrator(administrator);

        Ticket ticket = findTicket(ticketId);
        if (ticket.getStatus() != TicketStatus.SUBMITTED) {
            if (ticket.getActiveAssignment() != null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This request is already assigned; use the F13 reassignment workflow");
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only submitted requests can be assigned");
        }
        if (ticket.getActiveAssignment() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This request is already assigned; use the F13 reassignment workflow");
        }

        User technician = userRepository.findById(technicianId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "The selected technician does not exist"));
        if (!isAssignableTechnician(technician)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The selected user must be an active Maintenance Staff member");
        }

        TicketStatus previousStatus = ticket.getStatus();
        TicketAssignment assignment = ticket.assignTo(technician, administrator);
        ticket.setStatus(TicketStatus.ASSIGNED);
        ticket.touch();
        Ticket saved = ticketRepository.saveAndFlush(ticket);

        recordHistory(
                saved,
                TicketEventType.TICKET_ASSIGNED,
                administrator,
                "status=" + previousStatus + " | assignee=UNASSIGNED",
                "status=" + saved.getStatus()
                        + " | assigneeId=" + technician.getId()
                        + " | assignee=" + technician.getName(),
                null);
        notificationRepository.save(new InAppNotification(
                technician,
                administrator,
                saved,
                NotificationType.TICKET_ASSIGNED,
                "You were assigned maintenance request #" + saved.getId()
                        + ": " + saved.getTitle(),
                "TICKET_ASSIGNED:" + saved.getId() + ":" + assignment.getId()));
        return saved;
    }

    @Transactional
    public Ticket updateTicketStatus(Long ticketId, TicketStatus newStatus, User technician) {
        if (!technician.hasRole(Role.MAINTENANCE_STAFF)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only assigned maintenance staff can update request status");
        }

        Ticket ticket = findTicket(ticketId);
        TicketAssignment assignment = ticket.getActiveAssignment();
        if (assignment == null || !assignment.getTechnician().getId().equals(technician.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only the assigned technician can update this request status");
        }

        TicketStatus previousStatus = ticket.getStatus();
        if (!isAllowedTechnicianStatusTransition(previousStatus, newStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Invalid status transition from " + previousStatus + " to " + newStatus);
        }

        ticket.setStatus(newStatus);
        ticket.touch();
        Ticket saved = ticketRepository.save(ticket);
        recordHistory(
                saved,
                TicketEventType.STATUS_CHANGED,
                technician,
                previousStatus.name(),
                newStatus.name(),
                null);

        NotificationType notificationType = newStatus == TicketStatus.COMPLETED
                ? NotificationType.TICKET_COMPLETED
                : NotificationType.STATUS_CHANGED;
        notificationRepository.save(new InAppNotification(
                saved.getSubmittedBy(),
                technician,
                saved,
                notificationType,
                "Maintenance request #" + saved.getId()
                        + " status changed to " + newStatus,
                notificationType.name() + ":" + saved.getId() + ":" + newStatus));
        return saved;
    }

    @Transactional
    public Ticket updateTicket(Long ticketId, Long userId, TicketRequest request) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (isWorkStarted(ticket)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This request can no longer be edited because maintenance work has started");
        }

        if (ticket.getStatus() != TicketStatus.SUBMITTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only submitted requests can be edited");
        }

        String previousValue = snapshot(ticket);

        ticket.setTitle(normalize(request.title()));
        ticket.setDescription(normalize(request.description()));
        ticket.setCategory(request.category());
        ticket.setLocation(normalize(request.location()));
        ticket.setUrgency(request.urgency());
        ticket.touch();
        Ticket saved = ticketRepository.save(ticket);
        recordHistory(
                saved,
                TicketEventType.TICKET_UPDATED,
                saved.getSubmittedBy(),
                previousValue,
                snapshot(saved),
                null);
        return saved;
    }

    @Transactional
    public Ticket cancelTicket(Long ticketId, Long userId) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (ticket.getStatus() != TicketStatus.SUBMITTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This request can no longer be cancelled because maintenance work has started");
        }

        TicketStatus previousStatus = ticket.getStatus();
        ticket.setStatus(TicketStatus.CANCELLED);
        ticket.touch();
        Ticket saved = ticketRepository.save(ticket);
        recordHistory(
                saved,
                TicketEventType.TICKET_CANCELLED,
                saved.getSubmittedBy(),
                previousStatus.name(),
                saved.getStatus().name(),
                "Cancelled by requester");
        return saved;
    }

    @Transactional
    public void addTicketImage(Long ticketId, Long userId, MultipartFile file) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (ticket.getStatus() != TicketStatus.SUBMITTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Images can no longer be added because maintenance work has started");
        }

        int previousImageCount = ticket.getImages().size();
        try {
            ticket.addImage(new TicketImage(file.getBytes(), file.getContentType()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read image");
        }
        ticket.touch();
        ticketRepository.save(ticket);
        recordHistory(
                ticket,
                TicketEventType.IMAGE_ADDED,
                ticket.getSubmittedBy(),
                "Image count: " + previousImageCount,
                "Image count: " + ticket.getImages().size(),
                null);
    }

    @Transactional
    public void deleteTicketImage(Long ticketId, Long userId, Long imageId) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (ticket.getStatus() != TicketStatus.SUBMITTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Images can no longer be removed because maintenance work has started");
        }

        int previousImageCount = ticket.getImages().size();
        TicketImage image = ticket.getImages().stream()
                .filter(item -> item.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Image not found"));

        ticket.getImages().remove(image);
        ticket.touch();
        ticketRepository.save(ticket);
        recordHistory(
                ticket,
                TicketEventType.IMAGE_REMOVED,
                ticket.getSubmittedBy(),
                "Image count: " + previousImageCount,
                "Image count: " + ticket.getImages().size(),
                null);
    }

    @Transactional(readOnly = true)
    public TicketImage getTicketImage(Long ticketId, User viewer, Long imageId) {
        Ticket ticket = getTicketForViewer(ticketId, viewer);

        return ticket.getImages().stream()
                .filter(image -> image.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Image not found"));
    }

    private Ticket getTicketOwnedByUser(Long ticketId, Long userId) {
        Ticket ticket = findTicket(ticketId);

        if (!ticket.getSubmittedBy().getId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You do not own this request");
        }

        return ticket;
    }

    private Ticket findTicket(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Ticket not found"));
    }

    private boolean isWorkStarted(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.ASSIGNED
                || ticket.getStatus() == TicketStatus.IN_PROGRESS
                || ticket.getStatus() == TicketStatus.COMPLETED;
    }

    private boolean canViewAllTickets(User viewer) {
        return viewer.hasRole(Role.ADMINISTRATOR)
                || viewer.hasRole(Role.MANAGER);
    }

    private boolean isAssignedTo(Ticket ticket, User viewer) {
        TicketAssignment assignment = ticket.getActiveAssignment();
        return viewer.hasRole(Role.MAINTENANCE_STAFF)
                && assignment != null
                && assignment.getTechnician().getId().equals(viewer.getId());
    }

    private boolean isAssignableTechnician(User user) {
        return user.getAccountStatus() == AccountStatus.ACTIVE
                && user.hasRole(Role.MAINTENANCE_STAFF);
    }

    private boolean isAllowedTechnicianStatusTransition(
            TicketStatus previousStatus,
            TicketStatus newStatus) {
        return (previousStatus == TicketStatus.ASSIGNED && newStatus == TicketStatus.IN_PROGRESS)
                || (previousStatus == TicketStatus.IN_PROGRESS && newStatus == TicketStatus.COMPLETED);
    }

    private void requireAdministrator(User user) {
        if (!user.hasRole(Role.ADMINISTRATOR)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only an administrator can assign maintenance requests");
        }
    }

    private void recordHistory(
            Ticket ticket,
            TicketEventType eventType,
            User actor,
            String previousValue,
            String newValue,
            String reason) {
        ticketHistoryRepository.save(new TicketHistory(
                ticket,
                eventType,
                actor,
                previousValue,
                newValue,
                reason));
    }

    private String snapshot(Ticket ticket) {
        return "status=" + ticket.getStatus()
                + " | title=" + ticket.getTitle()
                + " | description=" + ticket.getDescription()
                + " | category=" + ticket.getCategory()
                + " | location=" + ticket.getLocation()
                + " | urgency=" + ticket.getUrgency()
                + " | operationalPriority=" + ticket.getOperationalPriority();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeBlankToNull(String value) {
        String normalized = normalize(value);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }
}
