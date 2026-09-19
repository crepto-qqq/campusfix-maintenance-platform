package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:f16-completion-evidence;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "campusfix.bootstrap-admin.password=integration-test-admin-password",
            "campusfix.registration.email-verification.required=false"
        })
class F16CompletionEvidenceIntegrationTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");

    @LocalServerPort
    private int port;

    @Test
    void assignedTechnicianCanUploadAndVisibleUsersCanReadCompletionEvidence() throws Exception {
        HttpClient publicClient = newClient();
        long requesterId = register(publicClient, "F16 Requester", "f16.requester@test.local");
        long technicianId = register(publicClient, "F16 Technician", "f16.tech@test.local");
        long otherTechId = register(publicClient, "F16 Other", "f16.other@test.local");

        HttpClient requester = login("f16.requester@test.local");
        long ticketId = createTicket(requester);
        HttpClient admin = loginAdmin();
        makeTechnician(admin, technicianId);
        makeTechnician(admin, otherTechId);
        assertEquals(200, postJson(admin, "/api/tickets/" + ticketId + "/assignment",
                "{\"technicianId\":" + technicianId + "}").statusCode());

        HttpClient technician = login("f16.tech@test.local");
        HttpClient otherTech = login("f16.other@test.local");
        assertEquals(200, putJson(technician, "/api/tickets/" + ticketId + "/status",
                "{\"status\":\"IN_PROGRESS\"}").statusCode());

        assertEquals(403, uploadEvidence(otherTech, ticketId).statusCode());
        HttpResponse<String> uploaded = uploadEvidence(technician, ticketId);
        assertEquals(201, uploaded.statusCode());
        long evidenceId = extractId(uploaded.body());
        assertTrue(uploaded.body().contains("Replaced damaged fitting and tested operation"));

        HttpResponse<String> requesterEvidence = get(requester,
                "/api/tickets/" + ticketId + "/completion-evidence");
        assertEquals(200, requesterEvidence.statusCode());
        assertTrue(requesterEvidence.body().contains("F16 Technician"));

        assertEquals(200, get(requester,
                "/api/tickets/" + ticketId + "/completion-evidence/" + evidenceId + "/image").statusCode());
        assertEquals(403, get(otherTech,
                "/api/tickets/" + ticketId + "/completion-evidence").statusCode());

        String history = get(admin, "/api/tickets/" + ticketId + "/history").body();
        assertTrue(history.contains("COMPLETION_EVIDENCE_ADDED"));
        assertTrue(requesterId > 0);
    }

    private HttpResponse<String> uploadEvidence(HttpClient client, long ticketId) throws Exception {
        String boundary = "CampusFixBoundary";
        byte[] image = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A};
        String beforeImage = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"repairNotes\"\r\n\r\n"
                + "Replaced damaged fitting and tested operation\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"evidence.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String afterImage = "\r\n--" + boundary + "--\r\n";
        byte[] prefix = beforeImage.getBytes(StandardCharsets.UTF_8);
        byte[] suffix = afterImage.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + image.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(image, 0, body, prefix.length, image.length);
        System.arraycopy(suffix, 0, body, prefix.length + image.length, suffix.length);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/tickets/" + ticketId + "/completion-evidence"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long register(HttpClient client, String name, String email) throws Exception {
        HttpResponse<String> response = postJson(client, "/api/auth/register",
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"password123\"}");
        assertEquals(201, response.statusCode());
        return extractId(response.body());
    }

    private long createTicket(HttpClient client) throws Exception {
        HttpResponse<String> response = postJson(client, "/api/tickets",
                "{\"title\":\"F16 evidence target\",\"description\":\"Completion evidence test\",\"category\":\"OTHER\",\"location\":\"F16 Lab\",\"urgency\":\"MEDIUM\"}");
        assertEquals(201, response.statusCode());
        return extractId(response.body());
    }

    private void makeTechnician(HttpClient admin, long userId) throws Exception {
        assertEquals(200, putJson(admin, "/api/admin/users/" + userId,
                "{\"identityType\":\"CAMPUS_STAFF\",\"role\":\"MAINTENANCE_STAFF\"}").statusCode());
    }

    private HttpClient login(String email) throws Exception {
        HttpClient client = newClient();
        assertEquals(200, postJson(client, "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"password123\"}").statusCode());
        return client;
    }

    private HttpClient loginAdmin() throws Exception {
        HttpClient client = newClient();
        assertEquals(200, postJson(client, "/api/auth/login",
                "{\"email\":\"admin@campusfix.edu\",\"password\":\"integration-test-admin-password\"}").statusCode());
        return client;
    }

    private HttpClient newClient() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(HttpClient client, String path, String json) throws Exception {
        return sendJson(client, path, "POST", json);
    }

    private HttpResponse<String> putJson(HttpClient client, String path, String json) throws Exception {
        return sendJson(client, path, "PUT", json);
    }

    private HttpResponse<String> sendJson(HttpClient client, String path, String method, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private long extractId(String json) {
        Matcher matcher = ID_PATTERN.matcher(json);
        assertTrue(matcher.find(), "Response does not contain an ID: " + json);
        return Long.parseLong(matcher.group(1));
    }
}
