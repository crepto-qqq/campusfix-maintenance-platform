package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:technician-reviews;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "campusfix.bootstrap-admin.password=integration-test-admin-password",
        "campusfix.registration.email-verification.required=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TechnicianReviewIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void completedTicketAllowsOnlyItsRequesterToRateFinalTechnicianAndKeepsPublicViewAnonymous()
            throws Exception {
        long requesterId = register("Review Requester", "review.requester@test.local");
        register("Other Student", "review.other@test.local");
        long previousTechnicianId = register("Previous Technician", "review.previous@test.local");
        long technicianId = register("Review Technician", "review.tech@test.local");
        HttpClient admin = admin();
        grant(admin, previousTechnicianId, "MAINTENANCE_STAFF");
        grant(admin, technicianId, "GENERAL_USER", "MAINTENANCE_STAFF");
        HttpClient requester = login("review.requester@test.local");
        HttpClient other = login("review.other@test.local");
        HttpClient technician = login("review.tech@test.local");

        long ticketId = ticket(requester, "Private dorm room location", null);
        String reviewPath = "/api/reviews/tickets/" + ticketId + "/mine";
        String review = body(Map.of("score", 5, "comment", "Careful work and clear explanation"));
        assertEquals(401, send(client(), "PUT", reviewPath, review, null).statusCode());
        assertEquals(403, send(other, "PUT", reviewPath, review, null).statusCode());
        assertEquals(409, send(requester, "PUT", reviewPath, review, null).statusCode());
        assertEquals(200, send(admin, "POST", "/api/tickets/" + ticketId + "/assignment",
                body(Map.of("technicianId", previousTechnicianId)), null).statusCode());
        assertEquals(409, send(requester, "PUT", reviewPath, review, null).statusCode());
        assertEquals(200, send(admin, "POST", "/api/tickets/" + ticketId + "/reassignment",
                body(Map.of("technicianId", technicianId, "reason", "Previous technician unavailable")),
                null).statusCode());
        complete(technician, ticketId, "MAINTENANCE_STAFF");

        assertEquals(403, send(technician, "PUT", reviewPath, review, "MAINTENANCE_STAFF").statusCode());
        assertEquals(400, send(requester, "PUT", reviewPath, body(Map.of("score", 0)), null).statusCode());
        assertEquals(400, send(requester, "PUT", reviewPath, body(Map.of("score", 6)), null).statusCode());
        assertEquals(400, send(requester, "PUT", reviewPath,
                body(Map.of("score", 3, "comment", "x".repeat(1001))), null).statusCode());
        assertSuccess(send(requester, "PUT", reviewPath, review, null));
        JsonNode mine = responseJson(send(requester, "GET", reviewPath, "", null));
        assertEquals(5, mine.path("score").asInt());
        assertEquals(technicianId, mine.path("technicianId").asLong());
        assertEquals(0, responseJson(send(other, "GET", "/api/reviews/technicians/"
                + previousTechnicianId, "", null)).path("ratingCount").asInt());

        String publicDetail = send(other, "GET", "/api/reviews/technicians/" + technicianId,
                "", null).body();
        assertTrue(publicDetail.contains("Careful work and clear explanation"));
        assertFalse(publicDetail.contains("review.requester@test.local"));
        assertFalse(publicDetail.contains("Private dorm room location"));
        assertFalse(publicDetail.contains("\"submittedById\":" + requesterId));
        assertFalse(publicDetail.contains("\"authorId\""));
        assertFalse(publicDetail.contains("\"ticketId\""));
        String publicCatalog = send(other, "GET", "/api/reviews/technicians", "", null).body();
        assertTrue(publicCatalog.contains("Careful work and clear explanation"));
        assertFalse(publicCatalog.contains("\"authorId\""));
        assertFalse(publicCatalog.contains("\"ticketId\""));
        assertEquals(403, send(technician, "GET", "/api/reviews/technicians/" + technicianId,
                "", "MAINTENANCE_STAFF").statusCode());
        assertEquals(200, send(technician, "GET", "/api/reviews/technicians/" + technicianId,
                "", "GENERAL_USER").statusCode());
        String ownAverage = send(technician, "GET", "/api/reviews/me/average",
                "", "MAINTENANCE_STAFF").body();
        assertTrue(ownAverage.contains("5"));
        assertFalse(ownAverage.contains("Careful work and clear explanation"));
        assertEquals(403, send(technician, "GET", "/api/reviews/me/average",
                "", "GENERAL_USER").statusCode());
        assertTrue(send(admin, "GET", "/api/admin/reviews", "", null).body()
                .contains("review.requester@test.local"));
    }

    @Test
    void cannotRateOneselfAndAssignmentRanksAvailablePeopleBeforeBusyPeople() throws Exception {
        register("Ranking Requester", "ranking.requester@test.local");
        long highlyRatedId = register("Highly Rated", "ranking.high@test.local");
        long idleId = register("Idle Unrated", "ranking.idle@test.local");
        long busyId = register("Busy Unrated", "ranking.busy@test.local");
        long dualId = register("Dual Role", "ranking.dual@test.local");
        HttpClient admin = admin();
        grant(admin, highlyRatedId, "MAINTENANCE_STAFF");
        grant(admin, idleId, "MAINTENANCE_STAFF");
        grant(admin, busyId, "MAINTENANCE_STAFF");
        grant(admin, dualId, "GENERAL_USER", "MAINTENANCE_STAFF");
        HttpClient requester = login("ranking.requester@test.local");
        HttpClient high = login("ranking.high@test.local");
        HttpClient dual = login("ranking.dual@test.local");

        long completedId = ticket(requester, "Rated repair", null);
        assign(admin, completedId, highlyRatedId);
        complete(high, completedId, null);
        assertSuccess(send(requester, "PUT", "/api/reviews/tickets/" + completedId + "/mine",
                body(Map.of("score", 5)), null));
        long busyTicketId = ticket(requester, "Still assigned repair", null);
        assign(admin, busyTicketId, busyId);
        long selfTicketId = ticket(dual, "Self completion", "GENERAL_USER");
        assign(admin, selfTicketId, dualId);
        complete(dual, selfTicketId, "MAINTENANCE_STAFF");
        assertEquals(403, send(dual, "PUT", "/api/reviews/tickets/" + selfTicketId + "/mine",
                body(Map.of("score", 5)), "GENERAL_USER").statusCode());

        JsonNode technicians = responseJson(send(admin, "GET", "/api/tickets/technicians", "", null));
        JsonNode highEntry = technician(technicians, highlyRatedId);
        JsonNode idleEntry = technician(technicians, idleId);
        JsonNode busyEntry = technician(technicians, busyId);
        assertEquals(1, highEntry.path("ratingCount").asInt());
        assertEquals(5.0, highEntry.path("averageRating").asDouble(), 0.001);
        assertEquals(3.75, highEntry.path("rankingScore").asDouble(), 0.001);
        assertTrue(highEntry.path("available").asBoolean());
        assertEquals(0, idleEntry.path("ratingCount").asInt());
        assertEquals(3.5, idleEntry.path("rankingScore").asDouble(), 0.001);
        assertEquals(1, busyEntry.path("openAssignmentCount").asInt());
        assertFalse(busyEntry.path("available").asBoolean());
        assertTrue(indexOf(technicians, highlyRatedId) < indexOf(technicians, idleId));
        assertTrue(indexOf(technicians, idleId) < indexOf(technicians, busyId));
        assertEquals(403, send(requester, "GET", "/api/tickets/technicians", "", null).statusCode());

        // A self-assigned account receives distinct requester and technician notices in their own workspaces.
        String studentNotifications = send(dual, "GET", "/api/notifications", "", "GENERAL_USER").body();
        String staffNotifications = send(dual, "GET", "/api/notifications", "", "MAINTENANCE_STAFF").body();
        assertTrue(studentNotifications.contains("was assigned to Dual Role"));
        assertFalse(studentNotifications.contains("You were assigned"));
        assertTrue(staffNotifications.contains("You were assigned"));
        assertFalse(staffNotifications.contains("was assigned to Dual Role"));
    }

    @Test
    void moderationPreservesOrRemovesTheScoreAndBanBlocksOnlyReviewWrites() throws Exception {
        long studentId = register("Moderation Student", "moderation.student@test.local");
        long technicianId = register("Moderation Technician", "moderation.tech@test.local");
        HttpClient admin = admin();
        grant(admin, studentId, "GENERAL_USER", "MAINTENANCE_STAFF");
        grant(admin, technicianId, "MAINTENANCE_STAFF");
        HttpClient student = login("moderation.student@test.local");
        HttpClient technician = login("moderation.tech@test.local");
        long ticketId = ticket(student, "Moderation case", "GENERAL_USER");
        assign(admin, ticketId, technicianId);
        complete(technician, ticketId, null);
        String ownPath = "/api/reviews/tickets/" + ticketId + "/mine";
        assertSuccess(send(student, "PUT", ownPath,
                body(Map.of("score", 1, "comment", "Remove this offensive phrase")), "GENERAL_USER"));
        long reviewId = responseJson(send(student, "GET", ownPath, "", "GENERAL_USER"))
                .path("id").asLong();
        assertTrue(reviewId > 0);
        String moderationPath = "/api/admin/reviews/" + reviewId + "/moderation";
        assertEquals(400, send(admin, "POST", moderationPath,
                body(Map.of("action", "REMOVE_TEXT", "reason", " ")), null).statusCode());
        assertSuccess(send(admin, "POST", moderationPath,
                body(Map.of("action", "REMOVE_TEXT", "reason", "Abusive wording")), null));
        String textRemovalNotices = send(student, "GET", "/api/notifications", "", "GENERAL_USER").body();
        assertTrue(textRemovalNotices.contains("REVIEW_TEXT_REMOVED"));
        assertFalse(textRemovalNotices.contains("\"type\":\"REVIEW_REMOVED\""));
        String publicAfterTextRemoval = send(student, "GET", "/api/reviews/technicians/" + technicianId,
                "", "GENERAL_USER").body();
        assertFalse(publicAfterTextRemoval.contains("Remove this offensive phrase"));
        assertEquals(1, technician(responseJson(send(admin, "GET", "/api/tickets/technicians", "", null)),
                technicianId).path("ratingCount").asInt());

        assertSuccess(send(student, "PUT", ownPath,
                body(Map.of("score", 2, "comment", "Revised appropriate review")), "GENERAL_USER"));
        JsonNode editedAfterTextRemoval = responseJson(send(student, "GET", ownPath,
                "", "GENERAL_USER"));
        assertEquals(2, editedAfterTextRemoval.path("score").asInt());
        assertEquals("TEXT_REMOVED", editedAfterTextRemoval.path("visibility").asText());
        String publicAfterEditedTextRemoval = send(student, "GET", "/api/reviews/technicians/" + technicianId,
                "", "GENERAL_USER").body();
        assertFalse(publicAfterEditedTextRemoval.contains("Revised appropriate review"));

        assertSuccess(send(admin, "POST", moderationPath,
                body(Map.of("action", "RESTORE", "reason", "Mistaken removal")), null));
        assertTrue(send(student, "GET", "/api/reviews/technicians/" + technicianId,
                "", "GENERAL_USER").body().contains("Revised appropriate review"));
        assertSuccess(send(admin, "POST", moderationPath,
                body(Map.of("action", "REMOVE_ALL", "reason", "Fabricated rating")), null));
        assertTrue(send(student, "GET", "/api/notifications", "", "GENERAL_USER").body()
                .contains("REVIEW_REMOVED"));
        assertEquals(0, technician(responseJson(send(admin, "GET", "/api/tickets/technicians", "", null)),
                technicianId).path("ratingCount").asInt());
        assertSuccess(send(student, "PUT", ownPath,
                body(Map.of("score", 4, "comment", "New appropriate review")), "GENERAL_USER"));
        assertEquals("ACTIVE", responseJson(send(student, "GET", ownPath, "", "GENERAL_USER"))
                .path("visibility").asText());
        assertTrue(send(student, "GET", "/api/reviews/technicians/" + technicianId,
                "", "GENERAL_USER").body().contains("New appropriate review"));
        String audit = send(admin, "GET", "/api/admin/reviews/" + reviewId + "/audit", "", null).body();
        assertTrue(audit.contains("REMOVE_TEXT"));
        assertTrue(audit.contains("RESTORE"));
        assertTrue(audit.contains("REMOVE_ALL"));
        assertTrue(audit.contains("Fabricated rating"));

        assertSuccess(send(admin, "POST", "/api/admin/reviews/students/" + studentId + "/ban",
                body(Map.of("reason", "Repeated abuse")), null));
        assertEquals(403, send(student, "PUT", ownPath,
                body(Map.of("score", 5)), "GENERAL_USER").statusCode());
        assertEquals(200, send(student, "GET", "/api/reviews/technicians/" + technicianId,
                "", "GENERAL_USER").statusCode());
        assertEquals(200, send(student, "GET", "/api/tickets", "", "GENERAL_USER").statusCode());
        assertSuccess(send(admin, "POST", "/api/admin/reviews/students/" + studentId + "/unban",
                body(Map.of("reason", "Appeal accepted")), null));
        assertSuccess(send(admin, "POST", "/api/admin/reviews/students/" + studentId + "/ban",
                body(Map.of("reason", "One-day suspension",
                        "expiresAt", LocalDateTime.now().plusDays(1)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))), null));
        JsonNode banState = responseJson(send(student, "GET", "/api/reviews/me/ban",
                "", "GENERAL_USER"));
        assertTrue(banState.path("banned").asBoolean());
        assertFalse(banState.path("expiresAt").isNull());
        // Move only the test database clock boundary, without waiting a day.
        jdbc.update("UPDATE review_bans SET expires_at = ? WHERE student_id = ? AND active = TRUE",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), studentId);
        assertFalse(responseJson(send(student, "GET", "/api/reviews/me/ban",
                "", "GENERAL_USER")).path("banned").asBoolean());
        assertSuccess(send(student, "PUT", ownPath,
                body(Map.of("score", 5)), "GENERAL_USER"));

        JsonNode studentNotices = responseJson(send(student, "GET", "/api/notifications", "", "GENERAL_USER"));
        JsonNode staffNotices = responseJson(send(student, "GET", "/api/notifications", "", "MAINTENANCE_STAFF"));
        assertTrue(studentNotices.path("unreadCount").asInt() > 0);
        assertFalse(staffNotices.toString().contains("Repeated abuse"));
        assertTrue(studentNotices.path("notifications").toString().contains("REVIEW_BANNED"));
        assertTrue(studentNotices.path("notifications").toString().contains("REVIEW_UNBANNED"));
        boolean banHasNoTicket = false;
        for (JsonNode notice : studentNotices.path("notifications")) {
            if ("REVIEW_BANNED".equals(notice.path("type").asText())) {
                banHasNoTicket = notice.path("ticketId").isNull();
            }
        }
        assertTrue(banHasNoTicket);
        long noticeId = studentNotices.path("notifications").get(0).path("id").asLong();
        assertTrue(noticeId > 0);
        assertEquals(404, send(student, "PUT", "/api/notifications/" + noticeId + "/read",
                "{}", "MAINTENANCE_STAFF").statusCode());
        assertEquals(204, send(student, "PUT", "/api/notifications/" + noticeId + "/read",
                "{}", "GENERAL_USER").statusCode());
        assertEquals(studentNotices.path("unreadCount").asInt() - 1,
                responseJson(send(student, "GET", "/api/notifications", "", "GENERAL_USER"))
                        .path("unreadCount").asInt());
    }

    @Test
    void reportResolutionIsAuditableAndMultiRoleAdminCannotModerateOwnReview() throws Exception {
        long authorId = register("Report Author", "report.author@test.local");
        long observerId = register("Report Observer", "report.observer@test.local");
        long technicianId = register("Report Technician", "report.tech@test.local");
        HttpClient admin = admin();
        grant(admin, authorId, "GENERAL_USER", "ADMINISTRATOR");
        grant(admin, technicianId, "MAINTENANCE_STAFF");
        HttpClient author = login("report.author@test.local");
        HttpClient observer = login("report.observer@test.local");
        HttpClient technician = login("report.tech@test.local");
        long ticketId = ticket(author, "Review self moderation", "GENERAL_USER");
        assign(admin, ticketId, technicianId);
        complete(technician, ticketId, null);
        String ownPath = "/api/reviews/tickets/" + ticketId + "/mine";
        assertSuccess(send(author, "PUT", ownPath,
                body(Map.of("score", 3, "comment", "Review to report")), "GENERAL_USER"));
        long reviewId = responseJson(send(author, "GET", ownPath, "", "GENERAL_USER"))
                .path("id").asLong();
        assertEquals(403, send(author, "POST", "/api/admin/reviews/" + reviewId + "/moderation",
                body(Map.of("action", "REMOVE_TEXT", "reason", "Self review")), "ADMINISTRATOR")
                .statusCode());
        assertEquals(403, send(author, "POST", "/api/admin/reviews/students/" + authorId + "/ban",
                body(Map.of("reason", "Self ban")), "ADMINISTRATOR").statusCode());

        String reportPath = "/api/reviews/" + reviewId + "/reports";
        HttpResponse<String> submittedReport = send(observer, "POST", reportPath,
                body(Map.of("reason", "Please review the comment")), null);
        assertSuccess(submittedReport);
        assertFalse(submittedReport.body().contains("\"authorId\""));
        assertFalse(submittedReport.body().contains("\"reporterId\""));
        assertFalse(submittedReport.body().contains("\"reporterName\""));
        assertFalse(submittedReport.body().contains("\"reason\""));
        assertSuccess(send(author, "PUT", ownPath,
                body(Map.of("score", 5, "comment", "Edited after the report")), "GENERAL_USER"));
        int repeatedReport = send(observer, "POST", reportPath,
                body(Map.of("reason", "Duplicate pending report")), null).statusCode();
        assertTrue(repeatedReport == 400 || repeatedReport == 409);
        JsonNode reports = responseJson(send(admin, "GET", "/api/admin/reviews/reports", "", null));
        assertTrue(reports.isArray());
        JsonNode reported = reportForReviewNode(reports, reviewId);
        assertEquals(3, reported.path("reportedScore").asInt());
        assertEquals("Review to report", reported.path("reportedComment").asText());
        assertFalse(reported.path("reportedComment").asText().contains("Edited after the report"));
        long reportId = reported.path("id").asLong();
        assertTrue(reportId > 0);
        assertTrue(send(observer, "GET", "/api/notifications", "", null).body()
                .contains("REVIEW_REPORT_SUBMITTED"));
        assertTrue(send(admin, "GET", "/api/notifications", "", null).body()
                .contains("REVIEW_REPORT_RECEIVED"));
        assertSuccess(send(admin, "POST", "/api/admin/reviews/reports/" + reportId + "/resolve",
                body(Map.of("action", "DISMISS", "reason", "No policy violation")), null));
        assertTrue(send(observer, "GET", "/api/notifications", "", null).body()
                .contains("REVIEW_REPORT_RESOLVED"));

        long secondTicketId = ticket(observer, "Second report target", null);
        assign(admin, secondTicketId, technicianId);
        complete(technician, secondTicketId, null);
        assertSuccess(send(observer, "PUT", "/api/reviews/tickets/" + secondTicketId + "/mine",
                body(Map.of("score", 4, "comment", "Second review text")), null));
        long secondReviewId = responseJson(send(observer, "GET",
                "/api/reviews/tickets/" + secondTicketId + "/mine", "", null)).path("id").asLong();
        assertSuccess(send(author, "POST", "/api/reviews/" + secondReviewId + "/reports",
                body(Map.of("reason", "Student mode report")), "GENERAL_USER"));
        assertSuccess(send(observer, "PUT", "/api/reviews/tickets/" + secondTicketId + "/mine",
                body(Map.of("score", 4, "comment", "Corrected after the report")), null));
        long ownReportId = reportForReview(responseJson(send(admin, "GET",
                "/api/admin/reviews/reports", "", null)), secondReviewId);
        String ownResolutionPath = "/api/admin/reviews/reports/" + ownReportId + "/resolve";
        assertEquals(403, send(author, "POST", ownResolutionPath,
                body(Map.of("action", "DISMISS", "reason", "Self report")), "ADMINISTRATOR")
                .statusCode());
        assertSuccess(send(admin, "POST", ownResolutionPath,
                body(Map.of("action", "BAN_AUTHOR", "reason", "Repeated offensive comments")), null));
        String publicAfterBan = send(author, "GET", "/api/reviews/technicians/" + technicianId,
                "", "GENERAL_USER").body();
        assertFalse(publicAfterBan.contains("Second review text"));
        assertTrue(publicAfterBan.contains("Corrected after the report"));
        assertTrue(responseJson(send(admin, "GET", "/api/admin/reviews/students/" + observerId + "/ban",
                "", null)).path("banned").asBoolean());
        assertTrue(send(author, "GET", "/api/notifications", "", "GENERAL_USER").body()
                .contains("REVIEW_REPORT_RESOLVED"));
    }

    @Test
    void concurrentAdministratorsCannotCreateTwoActiveBansAndInboxSettlesExpiredBan() throws Exception {
        long studentId = register("Concurrent Ban Student", "concurrent.student@test.local");
        long technicianId = register("Concurrent Ban Technician", "concurrent.tech@test.local");
        long secondAdminId = register("Second Ban Admin", "concurrent.admin@test.local");
        HttpClient firstAdmin = admin();
        grant(firstAdmin, technicianId, "MAINTENANCE_STAFF");
        grant(firstAdmin, secondAdminId, "ADMINISTRATOR");
        HttpClient secondAdmin = login("concurrent.admin@test.local");
        HttpClient student = login("concurrent.student@test.local");
        HttpClient technician = login("concurrent.tech@test.local");
        long ticketId = ticket(student, "Concurrency rating target", null);
        assign(firstAdmin, ticketId, technicianId);
        complete(technician, ticketId, null);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() ->
                concurrentBan(firstAdmin, studentId, "First administrator decision", ready, start));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() ->
                concurrentBan(secondAdmin, studentId, "Second administrator decision", ready, start));
        assertTrue(ready.await(15, TimeUnit.SECONDS), "Both administrators should reach the start gate");
        start.countDown();
        assertEquals(Set.of(200, 409), Set.of(first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_bans WHERE student_id = ? AND active = TRUE",
                Integer.class, studentId));
        String ownPath = "/api/reviews/tickets/" + ticketId + "/mine";
        assertEquals(403, send(student, "PUT", ownPath, body(Map.of("score", 4)), null).statusCode());

        assertSuccess(send(firstAdmin, "POST", "/api/admin/reviews/students/" + studentId + "/unban",
                body(Map.of("reason", "Review completed")), null));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_bans WHERE student_id = ? AND active = TRUE",
                Integer.class, studentId));
        assertSuccess(send(student, "PUT", ownPath, body(Map.of("score", 4)), null));

        assertSuccess(send(firstAdmin, "POST", "/api/admin/reviews/students/" + studentId + "/ban",
                body(Map.of("reason", "Temporary suspension",
                        "expiresAt", LocalDateTime.now().plusDays(1)
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")))), null));
        JsonNode beforeExpiry = responseJson(send(student, "GET", "/api/notifications", "", null));
        long beforeUnbanNotices = noticeCount(beforeExpiry, "REVIEW_UNBANNED");
        jdbc.update("UPDATE review_bans SET expires_at = ? WHERE student_id = ? AND active = TRUE",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), studentId);

        // Opening the inbox is enough to settle the expired ban and surface its notice.
        JsonNode afterExpiry = responseJson(send(student, "GET", "/api/notifications", "", null));
        assertEquals(beforeUnbanNotices + 1, noticeCount(afterExpiry, "REVIEW_UNBANNED"));
        assertTrue(afterExpiry.path("notifications").toString().contains("expired automatically"));
        assertFalse(responseJson(send(student, "GET", "/api/reviews/me/ban", "", null))
                .path("banned").asBoolean());
        assertSuccess(send(student, "PUT", ownPath, body(Map.of("score", 5)), null));
    }

    private int concurrentBan(HttpClient administrator, long studentId, String reason,
            CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(15, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
            return send(administrator, "POST", "/api/admin/reviews/students/" + studentId + "/ban",
                    body(Map.of("reason", reason)), null).statusCode();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private long noticeCount(JsonNode inbox, String type) {
        long count = 0;
        for (JsonNode notification : inbox.path("notifications")) {
            if (type.equals(notification.path("type").asText())) count++;
        }
        return count;
    }

    private void complete(HttpClient technician, long ticketId, String role) throws Exception {
        for (String status : new String[] {"IN_PROGRESS", "COMPLETED"}) {
            HttpResponse<String> response = send(technician, "PUT", "/api/tickets/" + ticketId + "/status",
                    body(Map.of("status", status)), role);
            assertEquals(200, response.statusCode(), response.body());
        }
    }

    private void assign(HttpClient admin, long ticketId, long technicianId) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/api/tickets/" + ticketId + "/assignment",
                body(Map.of("technicianId", technicianId)), null);
        assertEquals(200, response.statusCode(), response.body());
    }

    private void grant(HttpClient admin, long userId, String... roles) throws Exception {
        HttpResponse<String> response = send(admin, "PUT", "/api/admin/users/" + userId,
                body(Map.of("identityType", "CAMPUS_STAFF", "roles", roles)), null);
        assertEquals(200, response.statusCode(), response.body());
    }

    private long ticket(HttpClient owner, String title, String role) throws Exception {
        HttpResponse<String> response = send(owner, "POST", "/api/tickets",
                body(Map.of("title", title, "description", "Review acceptance test",
                        "category", "OTHER", "location", "Test campus", "urgency", "MEDIUM")), role);
        assertEquals(201, response.statusCode(), response.body());
        return responseJson(response).path("id").asLong();
    }

    private long register(String name, String email) throws Exception {
        HttpResponse<String> response = send(client(), "POST", "/api/auth/register",
                body(Map.of("name", name, "email", email, "password", "password123")), null);
        assertEquals(201, response.statusCode(), response.body());
        return responseJson(response).path("id").asLong();
    }

    private HttpClient admin() throws Exception {
        HttpClient client = client();
        HttpResponse<String> response = send(client, "POST", "/api/auth/login",
                body(Map.of("email", "admin@campusfix.edu",
                        "password", "integration-test-admin-password")), null);
        assertEquals(200, response.statusCode());
        return client;
    }

    private HttpClient login(String email) throws Exception {
        HttpClient client = client();
        HttpResponse<String> response = send(client, "POST", "/api/auth/login",
                body(Map.of("email", email, "password", "password123")), null);
        assertEquals(200, response.statusCode(), response.body());
        return client;
    }

    private HttpClient client() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> send(HttpClient client, String method, String path,
            String requestBody, String role) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (role != null) request.header("X-Active-Role", role);
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private JsonNode responseJson(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "Expected successful response, got " + response.statusCode() + " " + response.body());
        return json.readTree(response.body());
    }

    private JsonNode technician(JsonNode entries, long technicianId) {
        for (JsonNode entry : entries) {
            if (entry.path("id").asLong() == technicianId) return entry;
        }
        throw new AssertionError("Technician " + technicianId + " missing from " + entries);
    }

    private int indexOf(JsonNode entries, long technicianId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).path("id").asLong() == technicianId) return i;
        }
        throw new AssertionError("Technician " + technicianId + " missing from " + entries);
    }

    private long reportForReview(JsonNode reports, long reviewId) {
        return reportForReviewNode(reports, reviewId).path("id").asLong();
    }

    private JsonNode reportForReviewNode(JsonNode reports, long reviewId) {
        for (JsonNode report : reports) {
            if (report.path("reviewId").asLong() == reviewId) return report;
        }
        throw new AssertionError("Report for review " + reviewId + " missing from " + reports);
    }

    private void assertSuccess(HttpResponse<String> response) {
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201
                || response.statusCode() == 204,
                () -> "Expected successful response, got " + response.statusCode() + " " + response.body());
    }
}
