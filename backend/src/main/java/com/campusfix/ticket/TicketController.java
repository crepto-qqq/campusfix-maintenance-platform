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
import com.campusfix.user.UserService;

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
    private final UserService userService;

    public TicketController(TicketService ticketService, UserService userService) {
        this.ticketService = ticketService;
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketResponse createTicket(
            @Valid @RequestBody TicketRequest request,
            HttpSession session) {
        User user = userService.getProfile(getLoggedInUserId(session));
        Ticket ticket = ticketService.createTicket(request, user);
        return createTicketResponse(ticket);
    }

    @GetMapping
    public List<TicketResponse> getMyTickets(HttpSession session) {
        Long userId = getLoggedInUserId(session);
        return ticketService.getTicketsForUser(userId).stream()
                .map(this::createTicketResponse)
                .toList();
    }

    @GetMapping("/{ticketId}")
    public TicketResponse getTicket(
            @PathVariable Long ticketId,
            HttpSession session) {
        Ticket ticket = ticketService.getTicketForUser(
                ticketId,
                getLoggedInUserId(session));
        return createTicketResponse(ticket);
    }

    @PutMapping("/{ticketId}")
    public TicketResponse updateTicket(
            @PathVariable Long ticketId,
            @Valid @RequestBody TicketRequest request,
            HttpSession session) {
        Ticket ticket = ticketService.updateTicket(
                ticketId,
                getLoggedInUserId(session),
                request);
        return createTicketResponse(ticket);
    }

    @PostMapping("/{ticketId}/cancel")
    public TicketResponse cancelTicket(
            @PathVariable Long ticketId,
            HttpSession session) {
        Ticket ticket = ticketService.cancelTicket(
                ticketId,
                getLoggedInUserId(session));
        return createTicketResponse(ticket);
    }

    @PostMapping(value = "/{ticketId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadImage(
            @PathVariable Long ticketId,
            @RequestParam("image") MultipartFile image,
            HttpSession session) {
        validateImage(image);

        Ticket ticket = ticketService.getTicketForUser(
                ticketId,
                getLoggedInUserId(session));

        if (ticket.getImages().size() >= MAX_IMAGES_PER_TICKET) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A request cannot have more than " + MAX_IMAGES_PER_TICKET + " images");
        }

        ticketService.addTicketImage(ticketId, getLoggedInUserId(session), image);
    }

    @DeleteMapping("/{ticketId}/images/{imageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(
            @PathVariable Long ticketId,
            @PathVariable Long imageId,
            HttpSession session) {
        ticketService.deleteTicketImage(
                ticketId,
                getLoggedInUserId(session),
                imageId);
    }

    @GetMapping("/{ticketId}/images/{imageId}")
    public ResponseEntity<byte[]> getImage(
            @PathVariable Long ticketId,
            @PathVariable Long imageId,
            HttpSession session) {
        TicketImage image = ticketService.getTicketImage(
                ticketId,
                getLoggedInUserId(session),
                imageId);

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

    private Long getLoggedInUserId(HttpSession session) {
        Object userId = session.getAttribute("userId");

        if (userId instanceof Long id) {
            return id;
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required");
    }

    private TicketResponse createTicketResponse(Ticket ticket) {
        List<Long> imageIds = ticket.getImages().stream()
                .map(TicketImage::getId)
                .toList();

        return new TicketResponse(
                ticket.getId(),
                ticket.getTitle(),
                ticket.getDescription(),
                ticket.getCategory(),
                ticket.getLocation(),
                ticket.getUrgency(),
                ticket.getStatus(),
                ticket.getCreatedAt(),
                ticket.getUpdatedAt(),
                imageIds);
    }

    public record TicketResponse(
            Long id,
            String title,
            String description,
            TicketCategory category,
            String location,
            UrgencyLevel urgency,
            TicketStatus status,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime updatedAt,
            List<Long> imageIds) {
    }
}