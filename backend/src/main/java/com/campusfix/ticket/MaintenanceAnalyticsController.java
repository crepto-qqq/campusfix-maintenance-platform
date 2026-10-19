package com.campusfix.ticket;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.campusfix.review.ReviewVisibility;
import com.campusfix.review.TechnicianReviewRepository;
import com.campusfix.user.AccessControlService;
import com.campusfix.user.AccountStatus;
import com.campusfix.user.Role;
import com.campusfix.user.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets/analytics")
@CrossOrigin(origins = {"http://localhost:5500", "http://127.0.0.1:5500"}, allowCredentials = "true")
public class MaintenanceAnalyticsController {
    private final AccessControlService access;
    private final TicketRepository tickets;
    private final TicketHistoryRepository history;
    private final TicketAssignmentRepository assignments;
    private final TechnicianReviewRepository reviews;
    private final UserRepository users;

    public MaintenanceAnalyticsController(AccessControlService access, TicketRepository tickets,
            TicketHistoryRepository history, TicketAssignmentRepository assignments,
            TechnicianReviewRepository reviews, UserRepository users) {
        this.access = access;
        this.tickets = tickets;
        this.history = history;
        this.assignments = assignments;
        this.reviews = reviews;
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public AnalyticsResponse overview(HttpSession session) {
        access.requireRole(session, Role.ADMINISTRATOR);
        List<CountBucket> statuses = buckets(tickets.countsByStatus());
        Map<String, Long> counts = statuses.stream()
                .collect(Collectors.toMap(CountBucket::label, CountBucket::count));
        long total = statuses.stream().mapToLong(CountBucket::count).sum();
        long finished = counts.getOrDefault("COMPLETED", 0L) + counts.getOrDefault("CLOSED", 0L);
        long eligible = total - counts.getOrDefault("CANCELLED", 0L);
        List<Object[]> completions = history.completionTimes(
                TicketEventType.STATUS_CHANGED, "COMPLETED", List.of(TicketStatus.COMPLETED, TicketStatus.CLOSED));
        double meanHours = completions.stream().mapToDouble(row ->
                Duration.between((LocalDateTime) row[0], (LocalDateTime) row[1]).toMillis() / 3_600_000.0).average().orElse(0);
        Object[] rating = reviews.ratingStatistics(ReviewVisibility.REMOVED).getFirst();
        Map<Long, Long> workloads = assignments.openWorkloads(
                List.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.REOPENED)).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> ((Number) row[1]).longValue()));
        List<StaffWorkload> staff = users.findAllWithAccessOrderByNameAndEmail().stream()
                .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE && user.hasRole(Role.MAINTENANCE_STAFF))
                .map(user -> new StaffWorkload(user.getId(), user.getName(), workloads.getOrDefault(user.getId(), 0L)))
                .toList();
        return new AnalyticsResponse(total, counts.getOrDefault("SUBMITTED", 0L), finished,
                eligible == 0 ? 0 : finished * 100.0 / eligible,
                completions.isEmpty() ? null : meanHours, completions.size(),
                rating[0] == null ? null : ((Number) rating[0]).doubleValue(), ((Number) rating[1]).longValue(),
                statuses, buckets(tickets.countsByCategory()), buckets(tickets.countsByLocation()), staff);
    }

    private static List<CountBucket> buckets(List<Object[]> rows) {
        return rows.stream().map(row -> new CountBucket(row[0].toString(), ((Number) row[1]).longValue())).toList();
    }

    public record CountBucket(String label, long count) {}
    public record StaffWorkload(Long technicianId, String name, long openAssignments) {}
    public record AnalyticsResponse(long totalRequests, long pendingRequests, long completedRequests,
            double completionRate, Double averageCompletionHours, long completionTimeSamples,
            Double averageRating, long ratingCount, List<CountBucket> requestsByStatus,
            List<CountBucket> requestsByCategory, List<CountBucket> requestsByLocation,
            List<StaffWorkload> staffWorkload) {}
}
