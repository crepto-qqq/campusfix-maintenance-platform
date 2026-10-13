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
            "spring.datasource.url=jdbc:h2:mem:f12-assignment;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "campusfix.bootstrap-admin.password=integration-test-admin-password",
            "campusfix.registration.email-verification.required=false"
        })
class F12AssignmentIntegrationTest {

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
    void f12MeetsInitialAssignmentAcceptanceCriteria() throws Exception {
        HttpClient publicClient = newSessionClient();
        long requesterId = register(publicClient, "F12 Requester", "f12.requester@test.local");
        long technicianId = register(publicClient, "F12 Technician", "f12.technician@test.local");
        long otherTechnicianId = register(publicClient, "F12 Other Technician", "f12.other.tech@test.local");
        long disabledTechnicianId = register(publicClient, "F12 Disabled Technician", "f12.disabled.tech@test.local");

        HttpClient requesterClient = login("f12.requester@test.local", "password123");
        long mainTicketId = createTicket(requesterClient, "F12 assignment target");
        long unassignedTicketId = createTicket(requesterClient, "F12 remains unassigned");
        long cancelledTicketId = createTicket(requesterClient, "F12 cancelled target");
        assertEquals(200, post(
                requesterClient,
                "/api/tickets/" + cancelledTicketId + "/cancel",
                "{}").statusCode());
        long closedTicketId = createTicket(requesterClient, "F12 closed target");
        jdbcTemplate.update("UPDATE tickets SET status = 'CLOSED' WHERE id = ?", closedTicketId);

        HttpClient adminClient = login("admin@campusfix.edu", "integration-test-admin-password");
        updateAccess(adminClient, technicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        updateAccess(adminClient, otherTechnicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        updateAccess(adminClient, disabledTechnicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        jdbcTemplate.update(
                "UPDATE app_users SET account_status = 'DISABLED' WHERE id = ?",
                disabledTechnicianId);

        HttpClient technicianClient = login("f12.technician@test.local", "password123");
        HttpClient otherTechnicianClient = login("f12.other.tech@test.local", "password123");

        // Only an authenticated Administrator can list assignable technicians.
        assertEquals(401, get(publicClient, "/api/tickets/technicians").statusCode());
        assertEquals(403, get(requesterClient, "/api/tickets/technicians").statusCode());
        String technicians = get(adminClient, "/api/tickets/technicians").body();
        assertTrue(technicians.contains("\"id\":" + technicianId));
        assertTrue(technicians.contains("\"id\":" + otherTechnicianId));
        assertFalse(technicians.contains("\"id\":" + requesterId));
        assertFalse(technicians.contains("\"id\":" + disabledTechnicianId));

        String assignmentPath = "/api/tickets/" + mainTicketId + "/assignment";
        String validAssignment = "{\"technicianId\":" + technicianId + "}";

        // Server-side authentication, role and input validation.
        assertEquals(401, post(publicClient, assignmentPath, validAssignment).statusCode());
        assertEquals(403, post(requesterClient, assignmentPath, validAssignment).statusCode());
        assertEquals(403, post(technicianClient, assignmentPath, validAssignment).statusCode());
        assertEquals(400, post(adminClient, assignmentPath, "{\"technicianId\":null}").statusCode());
        assertEquals(400, post(adminClient, assignmentPath, "{\"technicianId\":0}").statusCode());
        assertEquals(400, post(adminClient, assignmentPath, "{\"technicianId\":999999}").statusCode());
        assertEquals(400, post(
                adminClient,
                assignmentPath,
                "{\"technicianId\":" + requesterId + "}").statusCode());
        assertEquals(400, post(
                adminClient,
                assignmentPath,
                "{\"technicianId\":" + disabledTechnicianId + "}").statusCode());

        // Cancelled and closed tickets cannot be assigned.
        assertEquals(409, post(
                adminClient,
                "/api/tickets/" + cancelledTicketId + "/assignment",
                validAssignment).statusCode());
        assertEquals(409, post(
                adminClient,
                "/api/tickets/" + closedTicketId + "/assignment",
                validAssignment).statusCode());

        HttpResponse<String> assigned = post(adminClient, assignmentPath, validAssignment);
        assertEquals(200, assigned.statusCode());
        assertTrue(assigned.body().contains("\"status\":\"ASSIGNED\""));
        assertTrue(assigned.body().contains("\"assignedTechnicianId\":" + technicianId));
        assertTrue(assigned.body().contains("\"assignedTechnicianName\":\"F12 Technician\""));
        assertTrue(assigned.body().contains("\"assignedAt\":"));

        // Requester, Administrator and assigned Technician see the same assignment.
        assertTrue(get(requesterClient, "/api/tickets/" + mainTicketId).body()
                .contains("\"assignedTechnicianId\":" + technicianId));
        assertTrue(get(adminClient, "/api/tickets").body()
                .contains("\"assignedTechnicianId\":" + technicianId));
        String technicianTickets = get(technicianClient, "/api/tickets").body();
        assertTrue(technicianTickets.contains("F12 assignment target"));
        assertFalse(technicianTickets.contains("F12 remains unassigned"));
        assertEquals(200, get(technicianClient, "/api/tickets/" + mainTicketId).statusCode());
        assertEquals(403, get(otherTechnicianClient, "/api/tickets/" + mainTicketId).statusCode());
        assertEquals(403, get(otherTechnicianClient, "/api/tickets/" + mainTicketId + "/history").statusCode());

        String history = get(adminClient, "/api/tickets/" + mainTicketId + "/history").body();
        assertEquals(1, countOccurrences(history, "\"eventType\":\"TICKET_ASSIGNED\""));
        assertTrue(history.contains("\"ticketId\":" + mainTicketId));
        assertTrue(history.contains("status=SUBMITTED | assignee=UNASSIGNED"));
        assertTrue(history.contains("status=ASSIGNED | assigneeId=" + technicianId));
        assertTrue(history.contains("\"actorName\":\"Administrator\""));
        assertTrue(history.contains("\"occurredAt\":"));

        TicketAssignment persistedAssignment = assignmentRepository
                .findByTicket_IdAndActiveTrue(mainTicketId)
                .orElseThrow();
        assertEquals(technicianId, persistedAssignment.getTechnician().getId());
        assertNotNull(persistedAssignment.getAssignedAt());
        assertTrue(persistedAssignment.isActive());

        List<InAppNotification> notifications = notificationRepository
                .findByRecipient_IdOrderByCreatedAtDescIdDesc(technicianId);
        assertEquals(1, notifications.size());
        InAppNotification notification = notifications.getFirst();
        assertEquals(NotificationType.TICKET_ASSIGNED, notification.getType());
        assertEquals(mainTicketId, notification.getTicket().getId());
        assertEquals(technicianId, notification.getRecipient().getId());
        assertTrue(notification.getMessage().contains("F12 assignment target"));
        assertFalse(notification.isRead());

        // A second initial assignment must be rejected and must not duplicate side effects.
        assertEquals(409, post(
                adminClient,
                assignmentPath,
                "{\"technicianId\":" + otherTechnicianId + "}").statusCode());
        assertEquals(1, assignmentRepository.countByTicket_Id(mainTicketId));
        assertEquals(1, notificationRepository.countByTicket_IdAndType(
                mainTicketId,
                NotificationType.TICKET_ASSIGNED));
        String finalHistory = get(adminClient, "/api/tickets/" + mainTicketId + "/history").body();
        assertEquals(1, countOccurrences(finalHistory, "\"eventType\":\"TICKET_ASSIGNED\""));

        assertTrue(unassignedTicketId > 0);
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
                  "description":"Formal F12 assignment acceptance test",
                  "category":"OTHER",
                  "location":"F12 Test Location",
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
        HttpResponse<String> response = post(
                client,
                "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, response.statusCode());
        return client;
    }

    private HttpClient newSessionClient() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
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
