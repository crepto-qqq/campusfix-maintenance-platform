package com.campusfix.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class BrevoVerificationEmailGatewayTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsBrevoTransactionalEmailRequestWithoutExposingSecrets() throws Exception {
        server.createContext("/v3/smtp/email", exchange -> {
            capturedRequest.set(capture(exchange));
            respond(exchange, 201, "{\"messageId\":\"test-message-id\"}");
        });

        BrevoVerificationEmailGateway gateway = gateway("shared-secret-key");
        gateway.sendVerificationCode("student@example.com", "123456", 10);

        CapturedRequest request = capturedRequest.get();
        assertEquals("POST", request.method());
        assertEquals("shared-secret-key", request.apiKey());
        assertTrue(request.contentType().startsWith("application/json"));

        JsonNode payload = objectMapper.readTree(request.body());
        assertEquals("CampusFix", payload.path("sender").path("name").asText());
        assertEquals("campusfix.sender@example.com", payload.path("sender").path("email").asText());
        assertEquals("student@example.com", payload.path("to").get(0).path("email").asText());
        assertEquals("Your CampusFix verification code", payload.path("subject").asText());
        assertTrue(payload.path("textContent").asText().contains("123456"));
        assertTrue(payload.path("htmlContent").asText().contains("123456"));
        assertTrue(payload.path("textContent").asText().contains("10 minutes"));
        assertFalse(payload.toString().contains("shared-secret-key"));
    }

    @Test
    void rejectsMissingApiKeyBeforeCallingBrevo() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> gateway(" ").sendVerificationCode("student@example.com", "123456", 10));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        assertTrue(exception.getReason().contains("CAMPUSFIX_BREVO_API_KEY"));
    }

    @Test
    void mapsBrevoFailureToSafeServiceUnavailableResponse() {
        server.createContext("/v3/smtp/email", exchange ->
                respond(exchange, 400, "{\"message\":\"invalid sender\"}"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> gateway("shared-secret-key")
                        .sendVerificationCode("student@example.com", "123456", 10));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        assertEquals(
                "Verification email could not be delivered. Please try again later",
                exception.getReason());
        assertFalse(exception.getReason().contains("shared-secret-key"));
        assertFalse(exception.getReason().contains("123456"));
    }

    private BrevoVerificationEmailGateway gateway(String apiKey) {
        return new BrevoVerificationEmailGateway(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v3",
                apiKey,
                "CampusFix",
                "campusfix.sender@example.com");
    }

    private CapturedRequest capture(HttpExchange exchange) throws IOException {
        return new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("api-key"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(String method, String apiKey, String contentType, String body) {
    }
}
