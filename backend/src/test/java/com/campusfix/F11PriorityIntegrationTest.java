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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:f11-priority;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class F11PriorityIntegrationTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    @LocalServerPort
    private int port;

    @Test
    void f11MeetsPriorityClassificationAcceptanceCriteria() throws Exception {
        HttpClient publicClient = newSessionClient();
        long requesterId = register(
                publicClient,
                "Priority Requester",
                "priority.requester@test.local");
        long managerId = register(
                publicClient,
                "Priority Manager",
                "priority.manager@test.local");

        HttpClient requesterClient = login(
                "priority.requester@test.local",
                "password123");
        HttpResponse<String> created = post(
                requesterClient,
                "/api/tickets",
                """
                {
                  "title":"Electrical safety issue",
                  "description":"Sparks are visible near the laboratory socket",
                  "category":"ELECTRICAL",
                  "location":"Engineering Lab 2",
                  "urgency":"EMERGENCY",
                  "operationalPriority":"CRITICAL"
                }
                """);
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"urgency\":\"EMERGENCY\""));
        assertTrue(created.body().contains("\"operationalPriority\":null"));
        long ticketId = extractId(created.body());

        assertEquals(401, get(publicClient, "/api/tickets/priority-summary").statusCode());
        assertEquals(403, get(requesterClient, "/api/tickets/priority-summary").statusCode());

        // Authentication and role enforcement happen on the server.
        assertEquals(401, put(
                publicClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"LOW\"}").statusCode());
        assertEquals(403, put(
                requesterClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"LOW\"}").statusCode());

        HttpClient adminClient = login("admin@campusfix.edu", "admin12345");
        String unclassifiedSummary = get(adminClient, "/api/tickets/priority-summary").body();
        assertTrue(unclassifiedSummary.contains("\"unclassified\":1"));
        assertTrue(unclassifiedSummary.contains("\"high\":0"));
        assertEquals(400, put(
                adminClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"URGENT\"}").statusCode());
        assertEquals(400, put(
                adminClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":null}").statusCode());
        assertEquals(400, put(
                adminClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"LOW\",\"reason\":\"" + "x".repeat(1001) + "\"}")
                .statusCode());

        HttpResponse<String> firstUpdate = put(
                adminClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"MEDIUM\",\"reason\":\" Initial triage \"}");
        assertEquals(200, firstUpdate.statusCode());
        assertTrue(firstUpdate.body().contains("\"operationalPriority\":\"MEDIUM\""));
        assertTrue(firstUpdate.body().contains("\"urgency\":\"EMERGENCY\""));

        HttpResponse<String> secondUpdate = put(
                adminClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"HIGH\",\"reason\":\" Safety impact \"}");
        assertEquals(200, secondUpdate.statusCode());
        assertEquals(409, put(
                adminClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"HIGH\"}").statusCode());

        // List and detail APIs expose the same persisted priority to authorised viewers.
        String requesterDetail = get(
                requesterClient,
                "/api/tickets/" + ticketId).body();
        String requesterList = get(requesterClient, "/api/tickets").body();
        String adminDetail = get(adminClient, "/api/tickets/" + ticketId).body();
        assertTrue(requesterDetail.contains("\"operationalPriority\":\"HIGH\""));
        assertTrue(requesterList.contains("\"operationalPriority\":\"HIGH\""));
        assertTrue(adminDetail.contains("\"operationalPriority\":\"HIGH\""));
        String adminSummary = get(adminClient, "/api/tickets/priority-summary").body();
        assertTrue(adminSummary.contains("\"unclassified\":0"));
        assertTrue(adminSummary.contains("\"high\":1"));

        String history = get(
                adminClient,
                "/api/tickets/" + ticketId + "/history").body();
        assertEquals(2, countOccurrences(history, "\"eventType\":\"PRIORITY_CHANGED\""));
        assertTrue(history.contains("\"ticketId\":" + ticketId));
        assertTrue(history.contains("\"previousValue\":\"UNSET\""));
        assertTrue(history.contains("\"newValue\":\"MEDIUM\""));
        assertTrue(history.contains("\"previousValue\":\"MEDIUM\""));
        assertTrue(history.contains("\"newValue\":\"HIGH\""));
        assertTrue(history.contains("\"reason\":\"Safety impact\""));
        assertTrue(history.contains("\"actorName\":\"Administrator\""));
        assertTrue(history.contains("\"occurredAt\":"));

        // Manager can read the final priority but cannot modify it.
        assertEquals(200, put(
                adminClient,
                "/api/admin/users/" + managerId,
                "{\"identityType\":\"CAMPUS_STAFF\",\"role\":\"MANAGER\"}").statusCode());
        HttpClient managerClient = login("priority.manager@test.local", "password123");
        assertTrue(get(managerClient, "/api/tickets").body()
                .contains("\"operationalPriority\":\"HIGH\""));
        assertEquals(adminSummary, get(managerClient, "/api/tickets/priority-summary").body());
        assertEquals(403, put(
                managerClient,
                "/api/tickets/" + ticketId + "/priority",
                "{\"priority\":\"CRITICAL\"}").statusCode());

        String finalDetail = get(adminClient, "/api/tickets/" + ticketId).body();
        assertTrue(finalDetail.contains("\"operationalPriority\":\"HIGH\""));
        assertFalse(finalDetail.contains("\"operationalPriority\":\"CRITICAL\""));
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
