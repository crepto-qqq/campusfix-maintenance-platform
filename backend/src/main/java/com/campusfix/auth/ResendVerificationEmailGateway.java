package com.campusfix.auth;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ResendVerificationEmailGateway implements VerificationEmailGateway {

    private final RestClient restClient;
    private final String apiKey;
    private final String fromAddress;

    public ResendVerificationEmailGateway(
            @Value("${campusfix.email.resend.api-url:https://api.resend.com}") String apiUrl,
            @Value("${campusfix.email.resend.api-key:}") String apiKey,
            @Value("${campusfix.email.resend.from:CampusFix <user-4e69d735baaf@example.invalid>}") String fromAddress) {
        this.restClient = RestClient.builder().baseUrl(apiUrl).build();
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendVerificationCode(
            String recipientEmail,
            String verificationCode,
            long validityMinutes) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is not configured. Set CAMPUSFIX_RESEND_API_KEY before starting the backend");
        }

        String subject = "Your CampusFix verification code";
        String text = "Your CampusFix verification code is " + verificationCode
                + ". It expires in " + validityMinutes
                + " minutes. If you did not request this code, you can ignore this email.";
        Map<String, Object> payload = Map.of(
                "from", fromAddress,
                "to", List.of(recipientEmail),
                "subject", subject,
                "text", text);

        try {
            restClient.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
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
}
