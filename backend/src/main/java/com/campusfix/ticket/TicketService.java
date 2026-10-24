package com.campusfix.ticket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;

import com.campusfix.review.ReviewService;
import com.campusfix.review.ReviewService.RatingSummary;

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

    private static final int MAX_IMAGES_PER_TICKET = 5;

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final UserRepository userRepository;
    private final InAppNotificationRepository notificationRepository;
    private final CompletionEvidenceRepository completionEvidenceRepository;
    private final TicketAssignmentRepository assignmentRepository;
    private final ReviewService reviewService;

    public TicketService(
            TicketRepository ticketRepository,
            TicketHistoryRepository ticketHistoryRepository,
            UserRepository userRepository,
            InAppNotificationRepository notificationRepository,
            CompletionEvidenceRepository completionEvidenceRepository,
            TicketAssignmentRepository assignmentRepository,
            ReviewService reviewService) {
        this.ticketRepository = ticketRepository;
        this.ticketHistoryRepository = ticketHistoryRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.completionEvidenceRepository = completionEvidenceRepository;
        this.assignmentRepository = assignmentRepository;
        this.reviewService = reviewService;
    }

    @Transactional
    public Ticket createTicket(TicketRequest request, User submittedBy) {
        validateCoordinates(request);
        Ticket ticket = new Ticket(
                normalize(request.title()),
                normalize(request.description()),
                request.category(),
                normalize(request.location()),
                request.urgency(),
                submittedBy);
        applyLocationData(ticket, request);
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
    public List<Ticket> getTicketsForViewer(User viewer, Role activeRole) {
        requireViewerRole(viewer, activeRole);
        return switch (activeRole) {
            case GENERAL_USER -> getTicketsForUser(viewer.getId());
            case MAINTENANCE_STAFF ->
                    ticketRepository.findDistinctByAssignments_Technician_IdAndAssignments_ActiveTrueOrderByCreatedAtDesc(
                            viewer.getId());
            case ADMINISTRATOR -> ticketRepository.findAllByOrderByCreatedAtDesc();
            case MANAGER -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Manager is no longer a workspace role");
        };
    }

    @Transactional(readOnly = true)
    public Ticket getTicketForViewer(Long ticketId, User viewer, Role activeRole) {
        requireViewerRole(viewer, activeRole);
        Ticket ticket = findTicket(ticketId);
        boolean canView = switch (activeRole) {
            case GENERAL_USER -> ticket.getSubmittedBy().getId().equals(viewer.getId());
            case MAINTENANCE_STAFF -> isAssignedTo(ticket, viewer);
            case ADMINISTRATOR -> true;
            case MANAGER -> false;
        };
        if (!canView) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this ticket");
        }

        return ticket;
    }

    @Transactional(readOnly = true)
    public List<TicketHistory> getTicketHistory(Long ticketId, User viewer, Role activeRole) {
        getTicketForViewer(ticketId, viewer, activeRole);
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
    public TicketPrioritySummary getPrioritySummary(User viewer, Role activeRole) {
        requireViewerRole(viewer, activeRole);
        if (activeRole != Role.ADMINISTRATOR) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only administrators can view the priority summary");
        }

        return new TicketPrioritySummary(
                ticketRepository.countByOperationalPriorityIsNull(),
                ticketRepository.countByOperationalPriority(TicketPriority.LOW),
                ticketRepository.countByOperationalPriority(TicketPriority.MEDIUM),
                ticketRepository.countByOperationalPriority(TicketPriority.HIGH),
                ticketRepository.countByOperationalPriority(TicketPriority.CRITICAL));
    }

    @Transactional(readOnly = true)
    public List<TechnicianCandidate> getAssignableTechnicians(User administrator) {
        requireAdministrator(administrator);
        Map<Long, RatingSummary> ratings = reviewService.ratingSummaries();
        return userRepository.findAllWithAccessOrderByNameAndEmail().stream()
                .filter(this::isAssignableTechnician)
                .map(user -> {
                    RatingSummary rating = ratings.getOrDefault(user.getId(),
                            new RatingSummary(null, 0, 3.5));
                    long openAssignments = assignmentRepository.countOpenForTechnician(user.getId(),
                            List.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.REOPENED));
                    return new TechnicianCandidate(user, rating.averageRating(), rating.ratingCount(),
                            rating.rankingScore(), openAssignments, openAssignments == 0);
                })
                .sorted(Comparator.comparing(TechnicianCandidate::available).reversed()
                        .thenComparing(TechnicianCandidate::rankingScore, Comparator.reverseOrder())
                        .thenComparing(candidate -> candidate.user().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(candidate -> candidate.user().getId()))
                .toList();
    }

    public record TechnicianCandidate(User user, Double averageRating, long ratingCount,
            double rankingScore, long openAssignmentCount, boolean available) {}

    @Transactional
    public Ticket assignTicket(Long ticketId, Long technicianId, User administrator) {
        requireAdministrator(administrator);

        Ticket ticket = findTicketForUpdate(ticketId);
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

        User technician = requireAssignableTechnician(technicianId);

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
        notificationRepository.save(new InAppNotification(
                saved.getSubmittedBy(), administrator, saved, NotificationType.TICKET_ASSIGNED,
                "Your maintenance request #" + saved.getId() + " was assigned to " + technician.getName()
                        + ": " + saved.getTitle(),
                "TICKET_ASSIGNED_REQUESTER:" + saved.getId() + ":" + assignment.getId()));
        return saved;
    }

    @Transactional
    public Ticket reassignTicket(
            Long ticketId,
            Long technicianId,
            String reason,
            User administrator) {
        requireAdministrator(administrator);

        String normalizedReason = normalizeBlankToNull(reason);
        if (normalizedReason == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A reassignment reason is required");
        }

        Ticket ticket = findTicketForUpdate(ticketId);
        if (!isReassignableStatus(ticket.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only assigned, in-progress, or reopened requests can be reassigned");
        }

        TicketAssignment previousAssignment = ticket.getActiveAssignment();
        if (previousAssignment == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This request has no active assignment to replace");
        }
        if (previousAssignment.getTechnician().getId().equals(technicianId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Select a different technician for reassignment");
        }

        User newTechnician = requireAssignableTechnician(technicianId);
        User previousTechnician = previousAssignment.getTechnician();
        TicketStatus previousStatus = ticket.getStatus();

        previousAssignment.end(normalizedReason);
        TicketAssignment newAssignment = ticket.assignTo(newTechnician, administrator);
        ticket.setStatus(TicketStatus.ASSIGNED);
        ticket.touch();
        Ticket saved = ticketRepository.saveAndFlush(ticket);

        recordHistory(
                saved,
                TicketEventType.TICKET_REASSIGNED,
                administrator,
                assignmentSnapshot(previousStatus, previousTechnician),
                assignmentSnapshot(saved.getStatus(), newTechnician),
                normalizedReason);
        notifyReassignmentParticipants(
                saved,
                previousTechnician,
                newTechnician,
                administrator,
                newAssignment);
        return saved;
    }

    @Transactional
    public Ticket updateTicketStatus(Long ticketId, TicketStatus newStatus, User technician) {
        if (!technician.hasRole(Role.MAINTENANCE_STAFF)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only assigned maintenance staff can update request status");
        }

        Ticket ticket = findTicketForUpdate(ticketId);
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
                notificationType.name() + ":" + saved.getId()
                        + ":" + assignment.getId()
                        + ":" + newStatus));
        return saved;
    }

    @Transactional
    public CompletionEvidence addCompletionEvidence(
            Long ticketId, User technician, String repairNotes, MultipartFile image) {
        // Serialize the assignment check with administrator reassignment.
        Ticket ticket = findTicketForUpdate(ticketId);
        TicketAssignment assignment = ticket.getActiveAssignment();
        if (assignment == null || !assignment.getTechnician().getId().equals(technician.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the assigned technician can upload completion evidence");
        }
        if (ticket.getStatus() != TicketStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Completion evidence can only be uploaded while work is in progress");
        }
        String notes = normalize(repairNotes);
        if (notes == null || notes.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repair notes are required");
        }
        if (notes.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repair notes must not exceed 2000 characters");
        }
        try {
            CompletionEvidence evidence = completionEvidenceRepository.save(new CompletionEvidence(
                    ticket, technician, notes, image.getBytes(), image.getContentType()));
            ticket.touch();
            ticketRepository.save(ticket);
            recordHistory(ticket, TicketEventType.COMPLETION_EVIDENCE_ADDED, technician, null,
                    "Completion evidence #" + evidence.getId(),
                    notes.length() > 1000 ? notes.substring(0, 1000) : notes);
            return evidence;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read evidence image");
        }
    }

    @Transactional(readOnly = true)
    public List<CompletionEvidence> getCompletionEvidence(Long ticketId, User viewer, Role activeRole) {
        getTicketForViewer(ticketId, viewer, activeRole);
        return completionEvidenceRepository.findByTicketIdOrderByUploadedAtDesc(ticketId);
    }

    @Transactional(readOnly = true)
    public CompletionEvidence getCompletionEvidenceImage(Long ticketId, Long evidenceId, User viewer, Role activeRole) {
        getTicketForViewer(ticketId, viewer, activeRole);
        CompletionEvidence evidence = completionEvidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Completion evidence not found"));
        if (!evidence.getTicket().getId().equals(ticketId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Completion evidence not found");
        }
        return evidence;
    }

    @Transactional
    public Ticket updateTicket(Long ticketId, Long userId, TicketRequest request) {
        validateCoordinates(request);
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
        applyLocationData(ticket, request);
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

        if (ticket.getImages().size() >= MAX_IMAGES_PER_TICKET) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A request cannot have more than " + MAX_IMAGES_PER_TICKET + " images");
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
    public TicketImage getTicketImage(Long ticketId, User viewer, Long imageId, Role activeRole) {
        Ticket ticket = getTicketForViewer(ticketId, viewer, activeRole);

        return ticket.getImages().stream()
                .filter(image -> image.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Image not found"));
    }

    private Ticket getTicketOwnedByUser(Long ticketId, Long userId) {
        // Owner edits/cancellation serialize with assignment and conversation writes.
        Ticket ticket = findTicketForUpdate(ticketId);

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

    private Ticket findTicketForUpdate(Long ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Ticket not found"));
    }

    private boolean isWorkStarted(Ticket ticket) {
        return ticket.getStatus() == TicketStatus.ASSIGNED
                || ticket.getStatus() == TicketStatus.IN_PROGRESS
                || ticket.getStatus() == TicketStatus.COMPLETED;
    }

    private boolean isAssignedTo(Ticket ticket, User viewer) {
        TicketAssignment assignment = ticket.getActiveAssignment();
        return assignment != null
                && assignment.getTechnician().getId().equals(viewer.getId());
    }

    private void requireViewerRole(User viewer, Role activeRole) {
        if (activeRole == null || !activeRole.isSelectable() || !viewer.hasRole(activeRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This role is not available for your account");
        }
    }

    private boolean isAssignableTechnician(User user) {
        return user.getAccountStatus() == AccountStatus.ACTIVE
                && user.hasRole(Role.MAINTENANCE_STAFF);
    }

    private User requireAssignableTechnician(Long technicianId) {
        User technician = userRepository.findById(technicianId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "The selected technician does not exist"));
        if (!isAssignableTechnician(technician)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The selected user must be an active Maintenance Staff member");
        }
        return technician;
    }

    private boolean isReassignableStatus(TicketStatus status) {
        return status == TicketStatus.ASSIGNED
                || status == TicketStatus.IN_PROGRESS
                || status == TicketStatus.REOPENED;
    }

    private String assignmentSnapshot(TicketStatus status, User technician) {
        return "status=" + status
                + " | assigneeId=" + technician.getId()
                + " | assignee=" + technician.getName();
    }

    private void notifyReassignmentParticipants(
            Ticket ticket,
            User previousTechnician,
            User newTechnician,
            User administrator,
            TicketAssignment assignment) {
        Map<Long, User> recipients = new LinkedHashMap<>();
        recipients.put(previousTechnician.getId(), previousTechnician);
        recipients.put(newTechnician.getId(), newTechnician);
        recipients.put(ticket.getSubmittedBy().getId(), ticket.getSubmittedBy());

        recipients.values().forEach(recipient -> notificationRepository.save(new InAppNotification(
                recipient,
                administrator,
                ticket,
                NotificationType.TICKET_REASSIGNED,
                "Maintenance request #" + ticket.getId()
                        + " was reassigned from " + previousTechnician.getName()
                        + " to " + newTechnician.getName(),
                "TICKET_REASSIGNED:" + ticket.getId()
                        + ":" + assignment.getId()
                        + ":" + recipient.getId())));
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
                + " | coordinates=" + ticket.getLatitude() + "," + ticket.getLongitude()
                + " | locationDetails=" + ticket.getLocationDetails()
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

    private void applyLocationData(Ticket ticket, TicketRequest request) {
        ticket.setGooglePlaceId(normalizeBlankToNull(request.googlePlaceId()));
        ticket.setLatitude(request.latitude());
        ticket.setLongitude(request.longitude());
        ticket.setLocationDetails(normalizeBlankToNull(request.locationDetails()));
    }

    private void validateCoordinates(TicketRequest request) {
        if ((request.latitude() == null) != (request.longitude() == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Latitude and longitude must be provided together");
        }
    }
}
