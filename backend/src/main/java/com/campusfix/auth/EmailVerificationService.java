package com.campusfix.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.user.UserRepository;

@Service
public class EmailVerificationService {

    private final EmailVerificationChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final VerificationEmailGateway emailGateway;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration codeTtl;
    private final Duration resendCooldown;
    private final int maxFailedAttempts;

    public EmailVerificationService(
            EmailVerificationChallengeRepository challengeRepository,
            UserRepository userRepository,
            VerificationEmailGateway emailGateway,
            PasswordEncoder passwordEncoder,
            @Value("${campusfix.registration.email-verification.code-ttl:10m}") Duration codeTtl,
            @Value("${campusfix.registration.email-verification.resend-cooldown:60s}") Duration resendCooldown,
            @Value("${campusfix.registration.email-verification.max-failed-attempts:5}") int maxFailedAttempts) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.emailGateway = emailGateway;
        this.passwordEncoder = passwordEncoder;
        this.codeTtl = codeTtl;
        this.resendCooldown = resendCooldown;
        this.maxFailedAttempts = maxFailedAttempts;
    }

    @Transactional
    public VerificationCodeDelivery requestCode(String email) {
        String normalizedEmail = normalizeEmail(email);

        // Return the same response for registered and unregistered addresses to
        // avoid exposing which email addresses already have accounts.
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            return new VerificationCodeDelivery(codeTtl.toMinutes(), resendCooldown.toSeconds());
        }

        LocalDateTime now = LocalDateTime.now();
        EmailVerificationChallenge challenge = challengeRepository.findByEmail(normalizedEmail)
                .orElse(null);

        if (challenge != null) {
            LocalDateTime nextAllowedAt = challenge.getLastSentAt().plus(resendCooldown);
            if (now.isBefore(nextAllowedAt)) {
                long secondsRemaining = Math.max(1, Duration.between(now, nextAllowedAt).toSeconds());
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Please wait " + secondsRemaining + " seconds before requesting another code");
            }
        }

        String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code);

        if (challenge == null) {
            challenge = new EmailVerificationChallenge(
                    normalizedEmail,
                    codeHash,
                    now.plus(codeTtl),
                    now);
        } else {
            challenge.replaceCode(codeHash, now.plus(codeTtl), now);
        }

        challengeRepository.saveAndFlush(challenge);
        emailGateway.sendVerificationCode(normalizedEmail, code, codeTtl.toMinutes());
        return new VerificationCodeDelivery(codeTtl.toMinutes(), resendCooldown.toSeconds());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VerificationResult verifyCode(String email, String submittedCode) {
        String normalizedEmail = normalizeEmail(email);
        if (submittedCode == null || !submittedCode.matches("^\\d{6}$")) {
            return VerificationResult.INVALID_FORMAT;
        }

        EmailVerificationChallenge challenge = challengeRepository.findByEmail(normalizedEmail)
                .orElse(null);
        if (challenge == null) {
            return VerificationResult.NOT_REQUESTED;
        }

        LocalDateTime now = LocalDateTime.now();
        if (!now.isBefore(challenge.getExpiresAt())) {
            challengeRepository.delete(challenge);
            return VerificationResult.EXPIRED;
        }

        if (challenge.getFailedAttempts() >= maxFailedAttempts) {
            return VerificationResult.TOO_MANY_ATTEMPTS;
        }

        boolean matches = passwordEncoder.matches(submittedCode, challenge.getCodeHash());

        if (!matches) {
            challenge.recordFailedAttempt();
            challengeRepository.save(challenge);
            return VerificationResult.INCORRECT;
        }

        return VerificationResult.VALID;
    }

    @Transactional
    public void consume(String email) {
        challengeRepository.findByEmail(normalizeEmail(email))
                .ifPresent(challengeRepository::delete);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record VerificationCodeDelivery(long expiresInMinutes, long retryAfterSeconds) {
    }

    public enum VerificationResult {
        VALID,
        INVALID_FORMAT,
        NOT_REQUESTED,
        EXPIRED,
        TOO_MANY_ATTEMPTS,
        INCORRECT
    }
}
