package com.campusfix;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:closeout;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "campusfix.bootstrap-admin.password=integration-test-admin-password",
    "campusfix.registration.email-verification.required=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CloseoutIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    @Test
    void conversationAndNotificationsFollowOwnershipActiveRoleAndReassignment() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        long ownerId = register("owner");
        long techId = register("tech");
        long replacementId = register("replacement");
        register("stranger");
        grant(admin, ownerId, "GENERAL_USER", "MAINTENANCE_STAFF");
        grant(admin, techId, "GENERAL_USER", "MAINTENANCE_STAFF");
        grant(admin, replacementId, "MAINTENANCE_STAFF");
        HttpClient owner = login("owner@test.local", "password123");
        HttpClient tech = login("tech@test.local", "password123");
        HttpClient replacement = login("replacement@test.local", "password123");
        HttpClient stranger = login("stranger@test.local", "password123");
        long id = ticket(owner, "Leaking tap", "PLUMBING", "Building A");
        String comments = "/api/tickets/" + id + "/comments";
        assertEquals(401, send(client(), "GET", comments, null, null).statusCode());
        assertEquals(403, send(stranger, "GET", comments, null, null).statusCode());
        assertEquals(201, send(owner, "POST", comments, "{\"body\":\"  Access after 9am  \"}", "GENERAL_USER").statusCode());
        assign(admin, id, techId);
        String ownerInbox = get(owner, "/api/notifications", "GENERAL_USER");
        assertTrue(ownerInbox.contains("was assigned to tech"));
        assertFalse(get(owner, "/api/notifications", "MAINTENANCE_STAFF").contains("was assigned"));
        assertTrue(get(tech, "/api/notifications", "MAINTENANCE_STAFF").contains("You were assigned"));
        assertFalse(get(tech, "/api/notifications", "GENERAL_USER").contains("You were assigned"));

        assertEquals(201, send(owner, "POST", comments, "{\"body\":\"The valve is behind the door\"}", "GENERAL_USER").statusCode());
        String techInbox = get(tech, "/api/notifications", "MAINTENANCE_STAFF");
        assertTrue(techInbox.contains("COMMENT_ADDED"));
        assertFalse(get(tech, "/api/notifications", "GENERAL_USER").contains("COMMENT_ADDED"));
        // A technician cannot mark a message read from the requester workspace.
        long messageNotice = jdbc.queryForObject("select id from in_app_notifications where recipient_id = ? and type = 'COMMENT_ADDED'", Long.class, techId);
        assertEquals(404, send(tech, "PUT", "/api/notifications/" + messageNotice + "/read", null, "GENERAL_USER").statusCode());
        assertEquals(204, send(tech, "PUT", "/api/notifications/" + messageNotice + "/read", null, "MAINTENANCE_STAFF").statusCode());
        assertEquals(201, send(tech, "POST", comments, "{\"body\":\"I will inspect it today\"}", "MAINTENANCE_STAFF").statusCode());
        assertTrue(get(owner, "/api/notifications", "GENERAL_USER").contains("COMMENT_ADDED"));
        assertFalse(get(owner, "/api/notifications", "MAINTENANCE_STAFF").contains("COMMENT_ADDED"));
        assertEquals(403, send(owner, "POST", comments, "{\"body\":\"Wrong mode\"}", "MAINTENANCE_STAFF").statusCode());
        assertEquals(403, send(admin, "POST", comments, "{\"body\":\"Admin write\"}", null).statusCode());
        String conversation = get(admin, comments, null);
        assertTrue(conversation.contains("\"body\":\"Access after 9am\""));
        assertTrue(conversation.indexOf("Access after 9am") < conversation.indexOf("inspect it today"));
        assertFalse(conversation.contains("@test.local"));

        assertEquals(200, send(admin, "POST", "/api/tickets/" + id + "/reassignment",
                "{\"technicianId\":" + replacementId + ",\"reason\":\"Cover shift\"}", null).statusCode());
        assertEquals(403, send(tech, "GET", comments, null, "MAINTENANCE_STAFF").statusCode());
        assertEquals(403, send(tech, "POST", comments, "{\"body\":\"No longer assigned\"}", "MAINTENANCE_STAFF").statusCode());
        assertFalse(get(tech, "/api/notifications", "MAINTENANCE_STAFF").contains("COMMENT_ADDED"));
        assertTrue(get(replacement, comments, null).contains("Access after 9am"));
        status(replacement, id, "IN_PROGRESS");
        status(replacement, id, "COMPLETED");
        assertEquals(200, send(owner, "GET", comments, null, "GENERAL_USER").statusCode());
        assertEquals(409, send(owner, "POST", comments, "{\"body\":\"After completion\"}", "GENERAL_USER").statusCode());
        assertEquals(409, send(replacement, "POST", comments, "{\"body\":\"After completion\"}", null).statusCode());
        assertTrue(get(owner, "/api/notifications", "GENERAL_USER").contains("TICKET_COMPLETED"));
    }

    @Test
    void commentsValidateContentAndSelfAssignmentKeepsOneNoticePerWorkspace() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        long id = register("dual");
        grant(admin, id, "GENERAL_USER", "MAINTENANCE_STAFF");
        HttpClient dual = login("dual@test.local", "password123");
        long ticket = ticket(dual, "Self assignment", "OTHER", "Building A");
        assign(admin, ticket, id);
        assertEquals(1, occurrences(get(dual, "/api/notifications", "GENERAL_USER"), "\"type\":\"TICKET_ASSIGNED\""));
        assertEquals(1, occurrences(get(dual, "/api/notifications", "MAINTENANCE_STAFF"), "\"type\":\"TICKET_ASSIGNED\""));
        String comments = "/api/tickets/" + ticket + "/comments";
        for (String invalid : new String[]{"{}", "{\"body\":null}", "{\"body\":\"   \"}", "{\"body\":\"" + "x".repeat(2001) + "\"}"}) {
            assertEquals(400, send(dual, "POST", comments, invalid, "GENERAL_USER").statusCode());
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from ticket_comments", Long.class));
        assertEquals(201, send(dual, "POST", comments, "{\"body\":\"" + "x".repeat(2000) + "\"}", "GENERAL_USER").statusCode());
        assertEquals(0L, jdbc.queryForObject("select count(*) from in_app_notifications where type = 'COMMENT_ADDED'", Long.class));
        assertEquals(409, send(admin, "POST", "/api/tickets/" + ticket + "/reassignment",
                "{\"technicianId\":" + id + ",\"reason\":\"Same person\"}", null).statusCode());
        // Ending a request keeps messages readable, but prevents new writes.
        long cancelled = ticket(dual, "Cancelled", "OTHER", "Building B");
        assertEquals(200, send(dual, "POST", "/api/tickets/" + cancelled + "/cancel", "{}", "GENERAL_USER").statusCode());
        assertEquals(409, send(dual, "POST", "/api/tickets/" + cancelled + "/comments", "{\"body\":\"Ended\"}", "GENERAL_USER").statusCode());
    }

    @Test
    void analyticsUsesRecordedCompletionTimesPublishedRatingsAndCurrentAssignments() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        assertEquals(401, send(client(), "GET", "/api/tickets/analytics", null, null).statusCode());
        String empty = get(admin, "/api/tickets/analytics", null);
        assertEquals(0, number(empty, "totalRequests"));
        assertTrue(empty.contains("\"averageCompletionHours\":null"));
        assertTrue(empty.contains("\"averageRating\":null"));
        register("reporter");
        long techId = register("worker");
        long idleId = register("idle");
        grant(admin, techId, "GENERAL_USER", "MAINTENANCE_STAFF", "ADMINISTRATOR");
        grant(admin, idleId, "MAINTENANCE_STAFF");
        HttpClient owner = login("reporter@test.local", "password123");
        HttpClient tech = login("worker@test.local", "password123");
        assertEquals(403, send(owner, "GET", "/api/tickets/analytics", null, null).statusCode());
        assertEquals(403, send(tech, "GET", "/api/tickets/analytics", null, "MAINTENANCE_STAFF").statusCode());
        long done = ticket(owner, "Completed", "PLUMBING", "Building A");
        assign(admin, done, techId);
        assertEquals(200, send(tech, "PUT", "/api/tickets/" + done + "/status", "{\"status\":\"IN_PROGRESS\"}", "MAINTENANCE_STAFF").statusCode());
        assertEquals(200, send(tech, "PUT", "/api/tickets/" + done + "/status", "{\"status\":\"COMPLETED\"}", "MAINTENANCE_STAFF").statusCode());
        long legacy = ticket(owner, "Legacy closed", "PLUMBING", "Building A");
        jdbc.update("update tickets set status = 'CLOSED' where id = ?", legacy);
        long cancelled = ticket(owner, "Cancelled", "IT", "Building B");
        assertEquals(200, send(owner, "POST", "/api/tickets/" + cancelled + "/cancel", "{}", null).statusCode());
        long active = ticket(owner, "Active", "IT", "Building B");
        assign(admin, active, techId);
        ticket(owner, "Pending", "OTHER", "Building B");
        jdbc.update("update tickets set created_at = timestamp '2026-10-01 08:00:00', updated_at = timestamp '2026-10-05 08:00:00' where id = ?", done);
        jdbc.update("update ticket_history set occurred_at = timestamp '2026-10-01 10:00:00' where ticket_id = ? and event_type = 'STATUS_CHANGED' and new_value = 'COMPLETED'", done);
        assertEquals(200, send(owner, "PUT", "/api/reviews/tickets/" + done + "/mine", "{\"score\":5,\"comment\":\"Good\"}", null).statusCode());
        String data = get(admin, "/api/tickets/analytics", null);
        assertEquals(5, number(data, "totalRequests"));
        assertEquals(1, number(data, "pendingRequests"));
        assertEquals(2, number(data, "completedRequests"));
        assertEquals(50, number(data, "completionRate"));
        assertEquals(2, number(data, "averageCompletionHours"));
        assertEquals(1, number(data, "completionTimeSamples"));
        assertEquals(5, number(data, "averageRating"));
        assertEquals(1, number(data, "ratingCount"));
        assertTrue(data.contains("\"label\":\"Building B\",\"count\":3"));
        assertTrue(data.contains("\"label\":\"PLUMBING\",\"count\":2"));
        assertTrue(data.contains("\"technicianId\":" + techId + ",\"name\":\"worker\",\"openAssignments\":1"));
        assertTrue(data.contains("\"technicianId\":" + idleId + ",\"name\":\"idle\",\"openAssignments\":0"));
        assertFalse(data.contains("@test.local"));
        jdbc.update("update technician_reviews set visibility = 'TEXT_REMOVED'");
        assertEquals(5, number(get(admin, "/api/tickets/analytics", null), "averageRating"));
        jdbc.update("update technician_reviews set visibility = 'REMOVED'");
        assertTrue(get(admin, "/api/tickets/analytics", null).contains("\"averageRating\":null"));
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    @Test
    void simultaneousCompletionCreatesOnlyOneHistoryEventAndNotification() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        register("concurrent-owner");
        long technicianId = register("concurrent-tech");
        grant(admin, technicianId, "MAINTENANCE_STAFF");
        HttpClient owner = login("concurrent-owner@test.local", "password123");
        HttpClient tech = login("concurrent-tech@test.local", "password123");
        long ticketId = ticket(owner, "Concurrent completion", "OTHER", "Building A");
        assign(admin, ticketId, technicianId);
        status(tech, ticketId, "IN_PROGRESS");
        CountDownLatch start = new CountDownLatch(1);
        var action = (java.util.function.Supplier<Integer>) () -> {
            try {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return send(tech, "PUT", "/api/tickets/" + ticketId + "/status", "{\"status\":\"COMPLETED\"}", null).statusCode();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        };
        var first = CompletableFuture.supplyAsync(action);
        var second = CompletableFuture.supplyAsync(action);
        start.countDown();
        var statuses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)).stream().sorted().toList();
        assertEquals(List.of(200, 409), statuses);
        assertEquals(1L, jdbc.queryForObject("select count(*) from ticket_history where ticket_id = ? and event_type = 'STATUS_CHANGED' and new_value = 'COMPLETED'", Long.class, ticketId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from in_app_notifications where ticket_id = ? and type = 'TICKET_COMPLETED'", Long.class, ticketId));
    }
    private long register(String name) throws Exception {
        HttpResponse<String> response = send(client(), "POST", "/api/auth/register", "{\"name\":\"" + name
                + "\",\"email\":\"" + name + "@test.local\",\"password\":\"password123\"}", null);
        assertEquals(201, response.statusCode(), response.body());
        return (long) number(response.body(), "id");
    }
    private HttpClient login(String email, String password) throws Exception {
        HttpClient client = client();
        assertEquals(200, send(client, "POST", "/api/auth/login", "{\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}", null).statusCode());
        return client;
    }
    private void grant(HttpClient admin, long userId, String... roles) throws Exception {
        assertEquals(200, send(admin, "PUT", "/api/admin/users/" + userId,
                "{\"identityType\":\"CAMPUS_STAFF\",\"roles\":[\"" + String.join("\",\"", roles) + "\"]}", null).statusCode());
    }
    private long ticket(HttpClient owner, String title, String category, String location) throws Exception {
        HttpResponse<String> response = send(owner, "POST", "/api/tickets", "{\"title\":\"" + title
                + "\",\"description\":\"Closeout acceptance\",\"category\":\"" + category
                + "\",\"location\":\"" + location + "\",\"urgency\":\"MEDIUM\"}", "GENERAL_USER");
        assertEquals(201, response.statusCode(), response.body());
        return (long) number(response.body(), "id");
    }
    private void assign(HttpClient admin, long ticketId, long technicianId) throws Exception {
        assertEquals(200, send(admin, "POST", "/api/tickets/" + ticketId + "/assignment",
                "{\"technicianId\":" + technicianId + "}", null).statusCode());
    }
    private void status(HttpClient tech, long id, String status) throws Exception {
        assertEquals(200, send(tech, "PUT", "/api/tickets/" + id + "/status", "{\"status\":\"" + status + "\"}", null).statusCode());
    }
    private String get(HttpClient client, String path, String role) throws Exception {
        HttpResponse<String> response = send(client, "GET", path, null, role);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
    private HttpResponse<String> send(HttpClient client, String method, String path, String body, String role) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (role != null) request.header("X-Active-Role", role);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static double number(String json, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*([0-9.]+)").matcher(json);
        assertTrue(matcher.find(), "Missing " + field + " in " + json);
        return Double.parseDouble(matcher.group(1));
    }
    private static int occurrences(String value, String part) {
        return (value.length() - value.replace(part, "").length()) / part.length();
    }
}
