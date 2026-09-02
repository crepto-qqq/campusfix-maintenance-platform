package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:f14-status-update;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "campusfix.bootstrap-admin.password=integration-test-admin-password"
        })
class F14StatusUpdateIntegrationTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    @LocalServerPort
    private int port;

    @Test
    void f14MeetsAssignedTechnicianStatusUpdateAcceptanceCriteria() throws Exception {
        HttpClient publicClient = newSessionClient();
        long requesterId = register(publicClient, "F14 Requester", "f14.requester@test.local");
        long technicianId = register(publicClient, "F14 Technician", "f14.technician@test.local");
        long otherTechnicianId = register(publicClient, "F14 Other Technician", "f14.other.tech@test.local");

        HttpClient requesterClient = login("f14.requester@test.local", "password123");
        long ticketId = createTicket(requesterClient, "F14 status target");

        HttpClient adminClient = login("admin@campusfix.edu", "integration-test-admin-password");
        updateAccess(adminClient, technicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        updateAccess(adminClient, otherTechnicianId, "CAMPUS_STAFF", "MAINTENANCE_STAFF");
        assertEquals(200, post(
                adminClient,
                "/api/tickets/" + ticketId + "/assignment",
                "{\"technicianId\":" + technicianId + "}").statusCode());

        HttpClient technicianClient = login("f14.technician@test.local", "password123");
        HttpClient otherTechnicianClient = login("f14.other.tech@test.local", "password123");

        String statusPath = "/api/tickets/" + ticketId + "/status";

        assertEquals(401, put(
                publicClient,
                statusPath,
                "{\"status\":\"IN_PROGRESS\"}").statusCode());
        assertEquals(403, put(
                requesterClient,
                statusPath,
                "{\"status\":\"IN_PROGRESS\"}").statusCode());
        assertEquals(403, put(
                adminClient,
                statusPath,
                "{\"status\":\"IN_PROGRESS\"}").statusCode());
        assertEquals(403, put(
                otherTechnicianClient,
                statusPath,
                "{\"status\":\"IN_PROGRESS\"}").statusCode());
        assertEquals(400, put(
                technicianClient,
                statusPath,
                "{\"status\":null}").statusCode());

        assertEquals(409, put(
                technicianClient,
                statusPath,
                "{\"status\":\"COMPLETED\"}").statusCode());

        HttpResponse<String> inProgress = put(
                technicianClient,
                statusPath,
                "{\"status\":\"IN_PROGRESS\"}");
        assertEquals(200, inProgress.statusCode());
        assertTrue(inProgress.body().contains("\"status\":\"IN_PROGRESS\""));

        assertEquals(409, put(
                technicianClient,
                statusPath,
                "{\"status\":\"ASSIGNED\"}").statusCode());

        HttpResponse<String> completed = put(
                technicianClient,
                statusPath,
                "{\"status\":\"COMPLETED\"}");
        assertEquals(200, completed.statusCode());
        assertTrue(completed.body().contains("\"status\":\"COMPLETED\""));

        assertEquals(409, put(
                technicianClient,
                statusPath,
                "{\"status\":\"IN_PROGRESS\"}").statusCode());

        String history = get(adminClient, "/api/tickets/" + ticketId + "/history").body();
        assertEquals(2, countOccurrences(history, "\"eventType\":\"STATUS_CHANGED\""));
        assertTrue(history.contains("\"previousValue\":\"ASSIGNED\""));
        assertTrue(history.contains("\"newValue\":\"IN_PROGRESS\""));
        assertTrue(history.contains("\"previousValue\":\"IN_PROGRESS\""));
        assertTrue(history.contains("\"newValue\":\"COMPLETED\""));
        assertTrue(history.contains("\"actorName\":\"F14 Technician\""));

        assertTrue(get(requesterClient, "/api/tickets/" + ticketId).body()
                .contains("\"status\":\"COMPLETED\""));
        assertTrue(get(technicianClient, "/api/tickets/" + ticketId).body()
                .contains("\"status\":\"COMPLETED\""));
        assertTrue(requesterId > 0);
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
                  "description":"Formal F14 status update acceptance test",
                  "category":"OTHER",
                  "location":"F14 Test Location",
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
