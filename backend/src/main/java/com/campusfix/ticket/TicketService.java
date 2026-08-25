package com.campusfix.ticket;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.user.User;
import com.campusfix.user.Role;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;

    public TicketService(
            TicketRepository ticketRepository,
            TicketHistoryRepository ticketHistoryRepository) {
        this.ticketRepository = ticketRepository;
        this.ticketHistoryRepository = ticketHistoryRepository;
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
        if (viewer.getRole() == Role.GENERAL_USER) {
            return getTicketsForUser(viewer.getId());
        }

        if (canViewAllTickets(viewer)) {
            return ticketRepository.findAllByOrderByCreatedAtDesc();
        }

        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Assigned-ticket access is not available until the assignment workflow is implemented");
    }

    @Transactional(readOnly = true)
    public Ticket getTicketForViewer(Long ticketId, User viewer) {
        Ticket ticket = findTicket(ticketId);
        boolean isOwner = ticket.getSubmittedBy().getId().equals(viewer.getId());

        if (!isOwner && !canViewAllTickets(viewer)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this ticket");
        }

        return ticket;
    }

    @Transactional(readOnly = true)
    public List<TicketHistory> getTicketHistory(Long ticketId, User viewer) {
        Ticket ticket = findTicket(ticketId);
        boolean isOwner = ticket.getSubmittedBy().getId().equals(viewer.getId());
        boolean canViewAll = canViewAllTickets(viewer);

        if (!isOwner && !canViewAll) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot view this ticket history");
        }

        return ticketHistoryRepository.findByTicketIdOrderByOccurredAtAscIdAsc(ticketId);
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
        return viewer.getRole() == Role.ADMINISTRATOR
                || viewer.getRole() == Role.MANAGER;
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
                + " | urgency=" + ticket.getUrgency();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
