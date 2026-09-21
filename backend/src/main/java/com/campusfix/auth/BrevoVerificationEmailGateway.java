package com.campusfix.auth;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BrevoVerificationEmailGateway implements VerificationEmailGateway {

    private final RestClient restClient;
    private final String apiKey;
    private final String senderName;
    private final String senderEmail;

    public BrevoVerificationEmailGateway(
            @Value("${campusfix.email.brevo.api-url:https://api.brevo.com/v3}") String apiUrl,
            @Value("${campusfix.email.brevo.api-key:}") String apiKey,
            @Value("${campusfix.email.brevo.sender-name:CampusFix}") String senderName,
            @Value("${campusfix.email.brevo.sender-email:}") String senderEmail) {
        this.restClient = RestClient.builder().baseUrl(apiUrl).build();
        this.apiKey = apiKey;
        this.senderName = senderName;
        this.senderEmail = senderEmail;
    }

    @Override
    public void sendVerificationCode(
            String recipientEmail,
            String verificationCode,
            long validityMinutes) {
        requireConfiguration();

        String subject = "Your CampusFix verification code";
        String text = "Your CampusFix verification code is " + verificationCode
                + ". It expires in " + validityMinutes
                + " minutes. If you did not request this code, you can ignore this email.";
        String html = "<p>Your CampusFix verification code is:</p>"
                + "<p style=\"font-size:24px;font-weight:700;letter-spacing:4px\">"
                + verificationCode
                + "</p><p>It expires in " + validityMinutes
                + " minutes. If you did not request this code, you can ignore this email.</p>";

        Map<String, Object> payload = Map.of(
                "sender", Map.of("name", senderName, "email", senderEmail),
                "to", List.of(Map.of("email", recipientEmail)),
                "subject", subject,
                "textContent", text,
                "htmlContent", html);

        try {
            restClient.post()
                    .uri("/smtp/email")
                    .header("api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Verification email could not be delivered. Please try again later");
        }
    }

    private void requireConfiguration() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is not configured. Set CAMPUSFIX_BREVO_API_KEY before starting the backend");
        }
        if (senderEmail == null || senderEmail.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is not configured. Set CAMPUSFIX_EMAIL_FROM_ADDRESS before starting the backend");
        }
    }
}
