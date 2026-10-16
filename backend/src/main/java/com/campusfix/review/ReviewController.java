package com.campusfix.review;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.campusfix.review.ReviewService.BanView;
import com.campusfix.review.ReviewService.OwnerReview;
import com.campusfix.review.ReviewService.ReportReceipt;
import com.campusfix.review.ReviewService.TechnicianSummary;
import com.campusfix.user.AccessControlService;
import com.campusfix.user.Role;
import com.campusfix.user.User;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/reviews")
@CrossOrigin(origins = {"http://localhost:5500", "http://127.0.0.1:5500"}, allowCredentials = "true")
public class ReviewController {
    private final ReviewService reviews;
    private final AccessControlService access;

    public ReviewController(ReviewService reviews, AccessControlService access) {
        this.reviews = reviews;
        this.access = access;
    }

    @GetMapping("/technicians")
    public List<TechnicianSummary> technicians(HttpSession session) {
        access.requireRole(session, Role.GENERAL_USER, Role.ADMINISTRATOR);
        return reviews.publicCatalog();
    }

    @GetMapping("/technicians/{technicianId}")
    public TechnicianSummary technician(@PathVariable Long technicianId, HttpSession session) {
        access.requireRole(session, Role.GENERAL_USER, Role.ADMINISTRATOR);
        return reviews.publicTechnician(technicianId);
    }

    @GetMapping("/me/average")
    public AverageOnly ownAverage(HttpSession session) {
        User technician = access.requireRole(session, Role.MAINTENANCE_STAFF);
        return new AverageOnly(reviews.ownAverage(technician).averageRating());
    }

    @GetMapping("/me/ban")
    public BanView ownBan(HttpSession session) {
        User student = access.requireRole(session, Role.GENERAL_USER);
        return reviews.ownBan(student);
    }

    @GetMapping("/tickets/{ticketId}/mine")
    public OwnerReview mine(@PathVariable Long ticketId, HttpSession session) {
        User student = access.requireRole(session, Role.GENERAL_USER);
        return reviews.mine(ticketId, student);
    }

    @PutMapping("/tickets/{ticketId}/mine")
    public OwnerReview upsert(@PathVariable Long ticketId, @RequestBody ReviewSubmission body,
            HttpSession session) {
        User student = access.requireRole(session, Role.GENERAL_USER);
        return reviews.upsert(ticketId, student, body.score(), body.comment());
    }

    @PostMapping("/{reviewId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportReceipt report(@PathVariable Long reviewId, @RequestBody ReportSubmission body,
            HttpSession session) {
        User student = access.requireRole(session, Role.GENERAL_USER);
        return reviews.report(reviewId, student, body.reason());
    }

    public record ReviewSubmission(Integer score, String comment) {}
    public record ReportSubmission(String reason) {}
    public record AverageOnly(Double averageRating) {}
}
