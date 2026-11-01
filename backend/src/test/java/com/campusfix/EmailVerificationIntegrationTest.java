package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.auth.EmailVerificationChallengeRepository;
import com.campusfix.auth.VerificationEmailGateway;
import com.campusfix.user.UserRepository;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:email-verification;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "campusfix.bootstrap-admin.password=integration-test-admin-password",
            "campusfix.registration.email-verification.required=true"
        })
@Import(EmailVerificationIntegrationTest.TestEmailConfiguration.class)
class EmailVerificationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CapturingVerificationEmailGateway emailGateway;

    @Autowired
    private EmailVerificationChallengeRepository challengeRepository;

    @Autowired
    private UserRepository userRepository;

    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetCapturedEmails() {
        emailGateway.clear();
    }

    @Test
    void registrationRequiresAndConsumesARealDeliveredCode() throws Exception {
        HttpResponse<String> withoutCode = register(
                "unverified@test.local",
                "password123",
                null);
        assertEquals(400, withoutCode.statusCode());
        assertFalse(userRepository.existsByEmailIgnoreCase("unverified@test.local"));

        HttpResponse<String> delivery = post(
                "/api/auth/verification-code",
                "{\"email\":\"verified@test.local\"}");
        assertEquals(200, delivery.statusCode());
        assertTrue(delivery.body().contains("\"expiresInMinutes\":10"));

        String deliveredCode = emailGateway.codeFor("verified@test.local");
        assertNotNull(deliveredCode);
        assertTrue(deliveredCode.matches("^\\d{6}$"));
        String storedHash = challengeRepository.findByEmail("verified@test.local")
                .orElseThrow()
                .getCodeHash();
        assertFalse(storedHash.equals(deliveredCode));
        assertTrue(storedHash.startsWith("$2"));

        assertEquals(429, post(
                "/api/auth/verification-code",
                "{\"email\":\"verified@test.local\"}").statusCode());

        assertEquals(400, register(
                "verified@test.local",
                "password123",
                differentCode(deliveredCode)).statusCode());
        assertFalse(userRepository.existsByEmailIgnoreCase("verified@test.local"));

        HttpResponse<String> registration = register(
                "verified@test.local",
                "password123",
                deliveredCode);
        assertEquals(201, registration.statusCode());
        assertTrue(registration.body().contains("\"identityType\":\"STUDENT\""));
        assertTrue(registration.body().contains("\"role\":\"GENERAL_USER\""));
        assertTrue(userRepository.existsByEmailIgnoreCase("verified@test.local"));
        assertTrue(challengeRepository.findByEmail("verified@test.local").isEmpty());
    }

    @Test
    void incorrectAttemptLimitIsPersistedAcrossRegistrationRequests() throws Exception {
        assertEquals(200, post(
                "/api/auth/verification-code",
                "{\"email\":\"locked@test.local\"}").statusCode());
        String deliveredCode = emailGateway.codeFor("locked@test.local");
        String wrongCode = differentCode(deliveredCode);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(400, register(
                    "locked@test.local",
                    "password123",
                    wrongCode).statusCode());
        }

        assertEquals(5, challengeRepository.findByEmail("locked@test.local")
                .orElseThrow()
                .getFailedAttempts());
        assertEquals(429, register(
                "locked@test.local",
                "password123",
                deliveredCode).statusCode());
        assertFalse(userRepository.existsByEmailIgnoreCase("locked@test.local"));
    }

    @Test
    void failedEmailDeliveryDoesNotLeaveAnUsableChallenge() throws Exception {
        emailGateway.failNextDelivery();

        HttpResponse<String> delivery = post(
                "/api/auth/verification-code",
                "{\"email\":\"delivery-failed@test.local\"}");

        assertEquals(503, delivery.statusCode());
        assertTrue(challengeRepository.findByEmail("delivery-failed@test.local").isEmpty());
        assertFalse(userRepository.existsByEmailIgnoreCase("delivery-failed@test.local"));
    }

    private HttpResponse<String> register(String email, String password, String code) throws Exception {
        String codeJson = code == null ? "null" : "\"" + code + "\"";
        return post(
                "/api/auth/register",
                "{\"name\":\"Email Test Student\",\"email\":\"" + email
                        + "\",\"password\":\"" + password
                        + "\",\"verificationCode\":" + codeJson + "}");
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String differentCode(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestEmailConfiguration {

        @Bean
        @Primary
        CapturingVerificationEmailGateway capturingVerificationEmailGateway() {
            return new CapturingVerificationEmailGateway();
        }
    }

    static class CapturingVerificationEmailGateway implements VerificationEmailGateway {

        private final Map<String, String> deliveredCodes = new ConcurrentHashMap<>();
        private boolean failNextDelivery;

        @Override
        public void sendVerificationCode(String recipientEmail, String verificationCode, long validityMinutes) {
            if (failNextDelivery) {
                failNextDelivery = false;
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Verification email could not be delivered. Please try again later");
            }
            deliveredCodes.put(recipientEmail, verificationCode);
        }

        String codeFor(String email) {
            return deliveredCodes.get(email);
        }

        void clear() {
            deliveredCodes.clear();
            failNextDelivery = false;
        }

        void failNextDelivery() {
            failNextDelivery = true;
        }
    }
}
