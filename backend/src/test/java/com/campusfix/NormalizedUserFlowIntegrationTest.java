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
            "spring.datasource.url=jdbc:h2:mem:normalized-user-flow;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
class NormalizedUserFlowIntegrationTest {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    @LocalServerPort
    private int port;

    @Test
    void frontendWriteFlowsRemainCompatibleAfterUserNormalization() throws Exception {
        HttpClient publicClient = newSessionClient();
        HttpResponse<String> registration = post(
                publicClient,
                "/api/auth/register",
                """
                {"name":"Integration Student","email":"integration@test.local","password":"password123"}
                """);
        assertEquals(201, registration.statusCode());
        assertTrue(registration.body().contains("\"identityType\":\"STUDENT\""));
        assertTrue(registration.body().contains("\"role\":\"GENERAL_USER\""));
        long userId = extractId(registration.body());

        HttpClient studentClient = newSessionClient();
        assertEquals(200, post(
                studentClient,
                "/api/auth/login",
                """
                {"email":"integration@test.local","password":"password123"}
                """).statusCode());

        HttpResponse<String> profile = put(
                studentClient,
                "/api/users/me",
                """
                {"name":"Updated Integration Student","contactInformation":"0400000000","profileDetails":"Normalized profile"}
                """);
        assertEquals(200, profile.statusCode());
        assertTrue(profile.body().contains("Updated Integration Student"));

        byte[] avatarBytes = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        assertEquals(204, uploadAvatar(studentClient, avatarBytes).statusCode());
        HttpResponse<byte[]> avatar = getBytes(studentClient, "/api/users/me/avatar");
        assertEquals(200, avatar.statusCode());
        assertEquals(avatarBytes.length, avatar.body().length);

        HttpResponse<String> ticket = post(
                studentClient,
                "/api/tickets",
                """
                {"title":"Integration request","description":"Normalized account ticket write","category":"IT","location":"Building A","urgency":"MEDIUM"}
                """);
        assertEquals(201, ticket.statusCode());
        assertTrue(ticket.body().contains("\"status\":\"SUBMITTED\""));
        long ticketId = extractId(ticket.body());
        assertEquals(200, get(studentClient, "/api/tickets/" + ticketId + "/history").statusCode());

        HttpClient adminClient = newSessionClient();
        assertEquals(200, post(
                adminClient,
                "/api/auth/login",
                """
                {"email":"admin@campusfix.edu","password":"admin12345"}
                """).statusCode());

        HttpResponse<String> accessUpdate = put(
                adminClient,
                "/api/admin/users/" + userId,
                """
                {"identityType":"CAMPUS_STAFF","role":"MAINTENANCE_STAFF"}
                """);
        assertEquals(200, accessUpdate.statusCode());
        assertTrue(accessUpdate.body().contains("\"identityType\":\"CAMPUS_STAFF\""));
        assertTrue(accessUpdate.body().contains("\"role\":\"MAINTENANCE_STAFF\""));

        HttpClient staffClient = newSessionClient();
        HttpResponse<String> staffLogin = post(
                staffClient,
                "/api/auth/login",
                """
                {"email":"integration@test.local","password":"password123"}
                """);
        assertEquals(200, staffLogin.statusCode());
        assertTrue(staffLogin.body().contains("\"role\":\"MAINTENANCE_STAFF\""));
    }

    private HttpClient newSessionClient() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(HttpClient client, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> uploadAvatar(HttpClient client, byte[] imageData) throws Exception {
        String boundary = "CampusFixTestBoundary";
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"avatar\"; filename=\"avatar.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/users/me/avatar"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(imageData),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();
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
}
