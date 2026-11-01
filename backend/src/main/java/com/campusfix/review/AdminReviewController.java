package com.campusfix.review;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campusfix.review.ReviewService.AdminReview;
import com.campusfix.review.ReviewService.AuditView;
import com.campusfix.review.ReviewService.BanView;
import com.campusfix.review.ReviewService.ModerationAction;
import com.campusfix.review.ReviewService.ReportAction;
import com.campusfix.review.ReviewService.ReportView;
import com.campusfix.user.AccessControlService;
import com.campusfix.user.Role;
import com.campusfix.user.User;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/admin/reviews")
@CrossOrigin(origins = {"http://localhost:5500", "http://127.0.0.1:5500"}, allowCredentials = "true")
public class AdminReviewController {
    private final ReviewService reviews;
    private final AccessControlService access;

    public AdminReviewController(ReviewService reviews, AccessControlService access) {
        this.reviews = reviews;
        this.access = access;
    }

    @GetMapping
    public List<AdminReview> reviews(HttpSession session) {
        access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.adminReviews();
    }

    @GetMapping("/reports")
    public List<ReportView> reports(HttpSession session) {
        access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.adminReports();
    }

    @GetMapping("/{reviewId}/audit")
    public List<AuditView> audit(@PathVariable Long reviewId, HttpSession session) {
        access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.audit(reviewId);
    }

    @PostMapping("/{reviewId}/moderation")
    public AdminReview moderate(@PathVariable Long reviewId, @RequestBody ModerationRequest body,
            HttpSession session) {
        User administrator = access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.moderate(reviewId, administrator, body.action(), body.reason());
    }

    @PostMapping("/reports/{reportId}/resolve")
    public ReportView resolve(@PathVariable Long reportId, @RequestBody ReportResolutionRequest body,
            HttpSession session) {
        User administrator = access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.resolveReport(reportId, administrator, body.action(), body.reason(), body.expiresAt());
    }

    @GetMapping("/students/{studentId}/ban")
    public BanView banState(@PathVariable Long studentId, HttpSession session) {
        access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.adminBan(studentId);
    }

    @PostMapping("/students/{studentId}/ban")
    public BanView ban(@PathVariable Long studentId, @RequestBody BanRequest body, HttpSession session) {
        User administrator = access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.ban(studentId, administrator, body.reason(), body.expiresAt());
    }

    @PostMapping("/students/{studentId}/unban")
    public BanView unban(@PathVariable Long studentId, @RequestBody UnbanRequest body, HttpSession session) {
        User administrator = access.requireRole(session, Role.ADMINISTRATOR);
        return reviews.unban(studentId, administrator, body.reason());
    }

    public record ModerationRequest(ModerationAction action, String reason) {}
    public record ReportResolutionRequest(ReportAction action, String reason, LocalDateTime expiresAt) {}
    public record BanRequest(String reason, LocalDateTime expiresAt) {}
    public record UnbanRequest(String reason) {}
}
