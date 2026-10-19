package com.campusfix;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CloseoutPersistenceTest {
    @TempDir Path directory;
    private int port;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fileDatabaseKeepsCommentsAssignmentsAndNotificationsAcrossRestart() throws Exception {
        String database = "jdbc:h2:file:" + directory.resolve("campusfix").toString();
        long ticketId;
        try (var first = start(database)) {
            port = first.getWebServer().getPort();
            HttpClient admin = login("admin@campusfix.edu", "restart-admin-password");
            HttpClient publicClient = client();
            JsonNode owner = send(publicClient, "POST", "/auth/register",
                    "{\"name\":\"Owner\",\"email\":\"owner@restart.test\",\"password\":\"password123\"}");
            JsonNode tech = send(publicClient, "POST", "/auth/register",
                    "{\"name\":\"Technician\",\"email\":\"tech@restart.test\",\"password\":\"password123\"}");
            assertTrue(owner.path("id").asLong() > 0);
            send(admin, "PUT", "/admin/users/" + tech.path("id").asLong(),
                    "{\"identityType\":\"CAMPUS_STAFF\",\"roles\":[\"MAINTENANCE_STAFF\"]}");
            HttpClient requester = login("owner@restart.test", "password123");
            ticketId = send(requester, "POST", "/tickets", """
                    {"title":"Persistent repair","description":"Restart acceptance","category":"OTHER",
                    "location":"Building A","urgency":"MEDIUM"}
                    """).path("id").asLong();
            send(admin, "POST", "/tickets/" + ticketId + "/assignment", "{\"technicianId\":" + tech.path("id").asLong() + "}");
            send(requester, "POST", "/tickets/" + ticketId + "/comments", "{\"body\":\"Please keep this message after restart\"}");
        }
        try (var second = start(database)) {
            port = second.getWebServer().getPort();
            HttpClient requester = login("owner@restart.test", "password123");
            HttpClient technician = login("tech@restart.test", "password123");
            JsonNode messages = send(technician, "GET", "/tickets/" + ticketId + "/comments", null);
            assertEquals(1, messages.size());
            assertEquals("Please keep this message after restart", messages.get(0).path("body").asText());
            assertEquals("ASSIGNED", send(requester, "GET", "/tickets/" + ticketId, null).path("status").asText());
            assertEquals(1, send(requester, "GET", "/notifications", null).path("unreadCount").asInt());
            assertEquals(2, send(technician, "GET", "/notifications", null).path("unreadCount").asInt());
            HttpClient admin = login("admin@campusfix.edu", "restart-admin-password");
            assertEquals(1, send(admin, "GET", "/tickets/analytics", null).path("totalRequests").asInt());
        }
    }

    private ServletWebServerApplicationContext start(String database) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(BackendApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.datasource.url=" + database,
                "--spring.jpa.hibernate.ddl-auto=update", "--campusfix.bootstrap-admin.password=restart-admin-password",
                "--campusfix.registration.email-verification.required=false", "--spring.main.banner-mode=off");
    }
    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpClient login(String email, String password) throws Exception {
        HttpClient client = client();
        send(client, "POST", "/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        return client;
    }
    private JsonNode send(HttpClient client, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path));
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<String> response = client.send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return json.readTree(response.body());
    }
}
