package com.campusfix.ticket;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.user.User;
import com.campusfix.user.AccessControlService;
import com.campusfix.user.Role;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tickets")
@CrossOrigin(
        origins = {"http://localhost:5500", "http://127.0.0.1:5500"},
        allowCredentials = "true"
)
public class TicketController {

    private static final long MAX_IMAGE_SIZE_BYTES = 15 * 1024 * 1024;
    private static final int MAX_IMAGES_PER_TICKET = 5;

    private final TicketService ticketService;
    private final AccessControlService accessControlService;

    public TicketController(
            TicketService ticketService,
            AccessControlService accessControlService) {
        this.ticketService = ticketService;
        this.accessControlService = accessControlService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse createTicket(
            @Valid @RequestBody TicketRequest request,
            HttpSession session) {
        User user = accessControlService.requireRole(session, Role.GENERAL_USER);
        Ticket ticket = ticketService.createTicket(request, user);
        return createTicketResponse(ticket);
    }

    @GetMapping
    public List<TicketResponse> getVisibleTickets(HttpSession session) {
        User viewer = accessControlService.requireAuthenticatedUser(session);
        return ticketService.getTicketsForViewer(viewer).stream()
                .map(this::createTicketResponse)
                .toList();
    }

    @GetMapping("/priority-summary")
    public TicketPrioritySummary getPrioritySummary(HttpSession session) {
        User viewer = accessControlService.requireAuthenticatedUser(session);
        return ticketService.getPrioritySummary(viewer);
    }

    @GetMapping("/technicians")
    public List<TechnicianResponse> getAssignableTechnicians(HttpSession session) {
        User administrator = accessControlService.requireRole(session, Role.ADMINISTRATOR);
        return ticketService.getAssignableTechnicians(administrator).stream()
                .map(user -> new TechnicianResponse(user.getId(), user.getName(), user.getEmail()))
                .toList();
    }

    @GetMapping("/{ticketId}")
    public TicketResponse getTicket(
            @PathVariable("ticketId") Long ticketId,
            HttpSession session) {
        User viewer = accessControlService.requireAuthenticatedUser(session);
        Ticket ticket = ticketService.getTicketForViewer(ticketId, viewer);
        return createTicketResponse(ticket);
    }

    @GetMapping("/{ticketId}/history")
    public List<TicketHistoryResponse> getTicketHistory(
            @PathVariable("ticketId") Long ticketId,
            HttpSession session) {
        User viewer = accessControlService.requireAuthenticatedUser(session);
        return ticketService.getTicketHistory(ticketId, viewer).stream()
                .map(this::createHistoryResponse)
                .toList();
    }

    @PutMapping("/{ticketId}/priority")
    public TicketResponse updatePriority(
            @PathVariable("ticketId") Long ticketId,
            @Valid @RequestBody TicketPriorityUpdateRequest request,
            HttpSession session) {
        User administrator = accessControlService.requireRole(session, Role.ADMINISTRATOR);
        Ticket ticket = ticketService.updateOperationalPriority(
                ticketId,
                request.priority(),
                request.reason(),
                administrator);
        return createTicketResponse(ticket);
    }

    @PostMapping("/{ticketId}/assignment")
    public TicketResponse assignTicket(
            @PathVariable("ticketId") Long ticketId,
            @Valid @RequestBody TicketAssignmentRequest request,
            HttpSession session) {
        User administrator = accessControlService.requireRole(session, Role.ADMINISTRATOR);
        return createTicketResponse(ticketService.assignTicket(
                ticketId,
                request.technicianId(),
                administrator));
    }

    @PostMapping("/{ticketId}/reassignment")
    public TicketResponse reassignTicket(
            @PathVariable("ticketId") Long ticketId,
            @Valid @RequestBody TicketReassignmentRequest request,
            HttpSession session) {
        User administrator = accessControlService.requireRole(session, Role.ADMINISTRATOR);
        return createTicketResponse(ticketService.reassignTicket(
                ticketId,
                request.technicianId(),
                request.reason(),
                administrator));
    }

    @PutMapping("/{ticketId}/status")
    public TicketResponse updateStatus(
            @PathVariable("ticketId") Long ticketId,
            @Valid @RequestBody TicketStatusUpdateRequest request,
            HttpSession session) {
        User technician = accessControlService.requireRole(session, Role.MAINTENANCE_STAFF);
        return createTicketResponse(ticketService.updateTicketStatus(
                ticketId,
                request.status(),
                technician));
    }

    @PutMapping("/{ticketId}")
    public TicketResponse updateTicket(
            @PathVariable("ticketId") Long ticketId,
            @Valid @RequestBody TicketRequest request,
            HttpSession session) {
        Ticket ticket = ticketService.updateTicket(
                ticketId,
                getRequesterId(session),
                request);
        return createTicketResponse(ticket);
    }

    @PostMapping("/{ticketId}/cancel")
    public TicketResponse cancelTicket(
            @PathVariable("ticketId") Long ticketId,
            HttpSession session) {
        Ticket ticket = ticketService.cancelTicket(
                ticketId,
                getRequesterId(session));
        return createTicketResponse(ticket);
    }

    @PostMapping(value = "/{ticketId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadImage(
            @PathVariable("ticketId") Long ticketId,
            @RequestParam("image") MultipartFile image,
            HttpSession session) {
        validateImage(image);

        Ticket ticket = ticketService.getTicketForUser(
                ticketId,
                getRequesterId(session));

        if (ticket.getImages().size() >= MAX_IMAGES_PER_TICKET) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A request cannot have more than " + MAX_IMAGES_PER_TICKET + " images");
        }

        ticketService.addTicketImage(ticketId, getRequesterId(session), image);
    }

    @DeleteMapping("/{ticketId}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(
            @PathVariable("ticketId") Long ticketId,
            @PathVariable("imageId") Long imageId,
            HttpSession session) {
        ticketService.deleteTicketImage(
                ticketId,
                getRequesterId(session),
                imageId);
    }

    @GetMapping("/{ticketId}/images/{imageId}")
    public ResponseEntity<byte[]> getImage(
            @PathVariable("ticketId") Long ticketId,
            @PathVariable("imageId") Long imageId,
            HttpSession session) {
        User viewer = accessControlService.requireAuthenticatedUser(session);
        TicketImage image = ticketService.getTicketImage(ticketId, viewer, imageId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.getContentType()))
                .body(image.getImageData());
    }

    private void validateImage(MultipartFile image) {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image is required");
        }

        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image must not exceed 15 MB");
        }

        String contentType = image.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an image");
        }
    }

    private Long getRequesterId(HttpSession session) {
        return accessControlService.requireRole(session, Role.GENERAL_USER).getId();
    }

    private TicketResponse createTicketResponse(Ticket ticket) {
        List<Long> imageIds = ticket.getImages().stream()
                .map(TicketImage::getId)
                .toList();
        TicketAssignment assignment = ticket.getActiveAssignment();

        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getCategory(),
                ticket.getLocation(),
                ticket.getUrgency(),
                ticket.getOperationalPriority(),
                assignment == null ? null : assignment.getTechnician().getId(),
                assignment == null ? null : assignment.getTechnician().getName(),
                assignment == null ? null : assignment.getTechnician().getEmail(),
                assignment == null ? null : assignment.getAssignedAt(),
                ticket.getStatus(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                ticket.getSubmittedBy().getId(),
                ticket.getSubmittedBy().getName(),
                ticket.getSubmittedBy().getEmail(),
                imageIds);
    }

    private TicketHistoryResponse createHistoryResponse(TicketHistory history) {
        return new TicketHistoryResponse(
                history.getId(),
                history.getTicketId(),
                history.getEventType(),
                history.getPreviousValue(),
                history.getNewValue(),
                history.getReason(),
                history.getActor().getId(),
                history.getActor().getName(),
                history.getOccurredAt());
    }

    public record TicketResponse(
            Long id,
            String title,
            String description,
            TicketCategory category,
            String location,
            UrgencyLevel urgency,
            TicketPriority operationalPriority,
            Long assignedTechnicianId,
            String assignedTechnicianName,
            String assignedTechnicianEmail,
            java.time.LocalDateTime assignedAt,
            TicketStatus status,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt,
            Long submittedById,
            String submittedByName,
            String submittedByEmail,
            List<Long> imageIds) {
    }

    public record TicketHistoryResponse(
            Long id,
            Long ticketId,
            TicketEventType eventType,
            String previousValue,
            String newValue,
            String reason,
            Long actorId,
            String actorName,
            java.time.LocalDateTime occurredAt) {
    }

    public record TechnicianResponse(
            Long id,
            String name,
            String email) {
    }
}
