package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.campusfix.notification.InAppNotification;
import com.campusfix.notification.InAppNotificationRepository;
import com.campusfix.notification.NotificationType;
import com.campusfix.ticket.TicketAssignment;
import com.campusfix.ticket.TicketAssignmentRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:f13-reassignment;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "campusfix.bootstrap-admin.password=integration-test-admin-password",
            "campusfix.registration.email-verification.required=false"
        })
class F13ReassignmentIntegrationTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    @LocalServerPort
    private int port;

    @Autowired
    private TicketAssignmentRepository assignmentRepository;

    @Autowired
    private InAppNotificationRepository notificationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void f13MeetsReassignmentAcceptanceCriteria() throws Exception {
        HttpClient publicClient = newSessionClient();
        long requesterId = register(publicClient, "F13 Requester", "f13.requester@test.local");
        long firstTechnicianId = register(publicClient, "F13 First Technician", "f13.first@test.local");
        long secondTechnicianId = register(publicClient, "F13 Second Technician", "f13.second@test.local");
        long disabledTechnicianId = register(publicClient, "F13 Disabled Technician", "f13.disabled@test.local");
        long ordinaryUserId = register(publicClient, "F13 Ordinary User", "f13.ordinary@test.local");
        long managerId = register(publicClient, "F13 Manager", "f13.manager@test.local");

        HttpClient requesterClient = login("f13.requester@test.local", "password123");
        long ticketId = createTicket(requesterClient, "F13 reassignment target");
        long unassignedTicketId = createTicket(requesterClient, "F13 unassigned target");
        long cancelledTicketId = createTicket(requesterClient, "F13 cancelled target");
        assertEquals(200, post(
                requesterClient,
                "/api/tickets/" + cancelledTicketId + "/cancel",
                "{}").statusCode());

        HttpClient adminClient = login("admin@campusfix.edu", "integration-test-admin-password");
        updateAccess(adminClient, firstTechnicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        updateAccess(adminClient, secondTechnicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        updateAccess(adminClient, disabledTechnicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        updateAccess(adminClient, managerId, "CAMPUS_STAFF", "MANAGER");
        jdbcTemplate.update(
                "UPDATE app_users SET account_status = 'DISABLED' WHERE id = ?",
                disabledTechnicianId);

        assertEquals(200, post(
                adminClient,
                "/api/tickets/" + ticketId + "/assignment",
                "{\"technicianId\":" + firstTechnicianId + "}").statusCode());

        HttpClient firstTechnicianClient = login("f13.first@test.local", "password123");
        HttpClient secondTechnicianClient = login("f13.second@test.local", "password123");
        HttpClient managerClient = login("f13.manager@test.local", "password123");
        assertEquals(200, put(
                firstTechnicianClient,
                "/api/tickets/" + ticketId + "/status",
                "{\"status\":\"IN_PROGRESS\"}").statusCode());

        String reassignmentPath = "/api/tickets/" + ticketId + "/reassignment";
        String validReassignment = "{\"technicianId\":" + secondTechnicianId
                + ",\"reason\":\"Original technician is unavailable\"}";

        // Authentication, role and request validation are enforced by the server.
        assertEquals(401, post(publicClient, reassignmentPath, validReassignment).statusCode());
        assertEquals(403, post(requesterClient, reassignmentPath, validReassignment).statusCode());
        assertEquals(403, post(firstTechnicianClient, reassignmentPath, validReassignment).statusCode());
        assertEquals(403, post(managerClient, reassignmentPath, validReassignment).statusCode());
        assertEquals(400, post(adminClient, reassignmentPath,
                "{\"technicianId\":null,\"reason\":\"Valid reason\"}").statusCode());
        assertEquals(400, post(adminClient, reassignmentPath,
                "{\"technicianId\":" + secondTechnicianId + ",\"reason\":\"   \"}").statusCode());
        assertEquals(400, post(adminClient, reassignmentPath,
                "{\"technicianId\":999999,\"reason\":\"Valid reason\"}").statusCode());
        assertEquals(400, post(adminClient, reassignmentPath,
                "{\"technicianId\":" + ordinaryUserId + ",\"reason\":\"Valid reason\"}").statusCode());
        assertEquals(400, post(adminClient, reassignmentPath,
                "{\"technicianId\":" + disabledTechnicianId + ",\"reason\":\"Valid reason\"}").statusCode());
        assertEquals(409, post(adminClient, reassignmentPath,
                "{\"technicianId\":" + firstTechnicianId + ",\"reason\":\"Same person\"}").statusCode());
        assertEquals(409, post(
                adminClient,
                "/api/tickets/" + unassignedTicketId + "/reassignment",
                validReassignment).statusCode());
        assertEquals(409, post(
                adminClient,
                "/api/tickets/" + cancelledTicketId + "/reassignment",
                validReassignment).statusCode());

        HttpResponse<String> reassigned = post(adminClient, reassignmentPath, validReassignment);
        assertEquals(200, reassigned.statusCode());
        assertTrue(reassigned.body().contains("\"status\":\"ASSIGNED\""));
        assertTrue(reassigned.body().contains("\"assignedTechnicianId\":" + secondTechnicianId));

        List<TicketAssignment> assignments = assignmentRepository.findAll().stream()
                .filter(assignment -> assignment.getTicket().getId().equals(ticketId))
                .toList();
        assertEquals(2, assignments.size());
        TicketAssignment oldAssignment = assignments.stream()
                .filter(assignment -> assignment.getTechnician().getId().equals(firstTechnicianId))
                .findFirst()
                .orElseThrow();
        TicketAssignment activeAssignment = assignmentRepository
                .findByTicket_IdAndActiveTrue(ticketId)
                .orElseThrow();
        assertFalse(oldAssignment.isActive());
        assertNotNull(oldAssignment.getEndedAt());
        assertEquals("Original technician is unavailable", oldAssignment.getReason());
        assertTrue(activeAssignment.isActive());
        assertEquals(secondTechnicianId, activeAssignment.getTechnician().getId());

        String history = get(adminClient, "/api/tickets/" + ticketId + "/history").body();
        assertEquals(1, countOccurrences(history, "\"eventType\":\"TICKET_REASSIGNED\""));
        assertTrue(history.contains("status=IN_PROGRESS | assigneeId=" + firstTechnicianId));
        assertTrue(history.contains("status=ASSIGNED | assigneeId=" + secondTechnicianId));
        assertTrue(history.contains("\"reason\":\"Original technician is unavailable\""));
        assertTrue(history.contains("\"actorName\":\"Administrator\""));

        // The old technician immediately loses access; the new technician gains it.
        assertEquals(403, get(firstTechnicianClient, "/api/tickets/" + ticketId).statusCode());
        assertEquals(200, get(secondTechnicianClient, "/api/tickets/" + ticketId).statusCode());
        assertEquals(403, put(
                firstTechnicianClient,
                "/api/tickets/" + ticketId + "/status",
                "{\"status\":\"IN_PROGRESS\"}").statusCode());
        assertEquals(200, put(
                secondTechnicianClient,
                "/api/tickets/" + ticketId + "/status",
                "{\"status\":\"IN_PROGRESS\"}").statusCode());

        assertReassignmentNotification(firstTechnicianId, ticketId);
        assertReassignmentNotification(secondTechnicianId, ticketId);
        assertReassignmentNotification(requesterId, ticketId);
        assertEquals(3, notificationRepository.countByTicket_IdAndType(
                ticketId,
                NotificationType.TICKET_REASSIGNED));

        // Reassigning to the current technician cannot duplicate records or side effects.
        assertEquals(409, post(adminClient, reassignmentPath,
                "{\"technicianId\":" + secondTechnicianId
                        + ",\"reason\":\"Duplicate attempt\"}").statusCode());
        assertEquals(2, assignmentRepository.countByTicket_Id(ticketId));
        assertEquals(3, notificationRepository.countByTicket_IdAndType(
                ticketId,
                NotificationType.TICKET_REASSIGNED));
    }

    private void assertReassignmentNotification(long recipientId, long ticketId) {
        InAppNotification notification = notificationRepository
                .findByRecipient_IdOrderByCreatedAtDescIdDesc(recipientId).stream()
                .filter(item -> item.getType() == NotificationType.TICKET_REASSIGNED)
                .findFirst()
                .orElseThrow();
        assertEquals(ticketId, notification.getTicket().getId());
        assertTrue(notification.getMessage().contains("F13 First Technician"));
        assertTrue(notification.getMessage().contains("F13 Second Technician"));
        assertFalse(notification.isRead());
    }

    private long register(HttpClient client, String name, String email) throws Exception {
        HttpResponse<String> response = post(
                client,
                "/api/auth/register",
                "{\"name\":\"" + name + "\",\"email\":\"" + email
                        + "\",\"password\":\"password123\"}");
        assertEquals(201, response.statusCode());
        return extractId(response.body());
    }

    private long createTicket(HttpClient client, String title) throws Exception {
        HttpResponse<String> response = post(
                client,
                "/api/tickets",
                """
                {
                  "title":"%s",
                  "description":"Formal F13 reassignment acceptance test",
                  "category":"OTHER",
                  "location":"F13 Test Location",
                  "urgency":"MEDIUM"
                }
                """.formatted(title));
        assertEquals(201, response.statusCode());
        return extractId(response.body());
    }

    private void updateAccess(
            HttpClient adminClient,
            long userId,
            String identity,
            String role) throws Exception {
        assertEquals(200, put(
                adminClient,
                "/api/admin/users/" + userId,
                "{\"identityType\":\"" + identity + "\",\"role\":\"" + role + "\"}")
                .statusCode());
    }

    private HttpClient login(String email, String password) throws Exception {
        HttpClient client = newSessionClient();
        assertEquals(200, post(
                client,
                "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .statusCode());
        return client;
    }

    private HttpClient newSessionClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return sendJson(client, path, "GET", "");
    }

    private HttpResponse<String> post(HttpClient client, String path, String json) throws Exception {
        return sendJson(client, path, "POST", json);
    }

    private HttpResponse<String> put(HttpClient client, String path, String json) throws Exception {
        return sendJson(client, path, "PUT", json);
    }

    private HttpResponse<String> sendJson(
            HttpClient client,
            String path,
            String method,
            String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private long extractId(String json) {
        Matcher matcher = ID_PATTERN.matcher(json);
        assertTrue(matcher.find(), "Response does not contain an ID: " + json);
        return Long.parseLong(matcher.group(1));
    }

    private int countOccurrences(String value, String fragment) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(fragment, index)) >= 0) {
            count++;
            index += fragment.length();
        }
        return count;
    }
}
