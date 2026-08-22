package com.campusfix.ticket;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.user.User;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;

    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
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
        return ticketRepository.save(ticket);
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

    @Transactional
    public Ticket updateTicket(Long ticketId, Long userId, TicketRequest request) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (isWorkStarted(ticket)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This request can no longer be edited because maintenance work has started");
        }

        ticket.setTitle(normalize(request.title()));
        ticket.setDescription(normalize(request.description()));
        ticket.setCategory(request.category());
        ticket.setLocation(normalize(request.location()));
        ticket.setUrgency(request.urgency());
        ticket.touch();
        return ticketRepository.save(ticket);
    }

    @Transactional
    public Ticket cancelTicket(Long ticketId, Long userId) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (isWorkStarted(ticket) || ticket.getStatus() == TicketStatus.COMPLETED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This request can no longer be cancelled because maintenance work has started");
        }

        ticket.setStatus(TicketStatus.CANCELLED);
        ticket.touch();
        return ticketRepository.save(ticket);
    }

    @Transactional
    public void addTicketImage(Long ticketId, Long userId, MultipartFile file) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (isWorkStarted(ticket)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Images can no longer be added because maintenance work has started");
        }

        try {
            ticket.addImage(new TicketImage(file.getBytes(), file.getContentType()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read image");
        }
        ticket.touch();
        ticketRepository.save(ticket);
    }

    @Transactional
    public void deleteTicketImage(Long ticketId, Long userId, Long imageId) {
        Ticket ticket = getTicketOwnedByUser(ticketId, userId);

        if (isWorkStarted(ticket)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Images can no longer be removed because maintenance work has started");
        }

        TicketImage image = ticket.getImages().stream()
                .filter(item -> item.getId().equals(imageId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Image not found"));

        ticket.getImages().remove(image);
        ticket.touch();
        ticketRepository.save(ticket);
    }

    @Transactional(readOnly = true)
    public TicketImage getTicketImage(Long ticketId, Long userId, Long imageId) {
        Ticket ticket = getTicketForUser(ticketId, userId);

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

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}