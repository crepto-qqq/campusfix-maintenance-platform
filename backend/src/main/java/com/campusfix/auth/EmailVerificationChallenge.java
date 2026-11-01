package com.campusfix.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "email_verification_challenges",
        indexes = @Index(name = "idx_email_verification_expires_at", columnList = "expires_at"))
public class EmailVerificationChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_sent_at", nullable = false)
    private LocalDateTime lastSentAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    protected EmailVerificationChallenge() {
    }

    public EmailVerificationChallenge(
            String email,
            String codeHash,
            LocalDateTime expiresAt,
            LocalDateTime lastSentAt) {
        this.email = email;
        replaceCode(codeHash, expiresAt, lastSentAt);
    }

    public String getCodeHash() {
        return codeHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getLastSentAt() {
        return lastSentAt;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public void replaceCode(
            String codeHash,
            LocalDateTime expiresAt,
            LocalDateTime lastSentAt) {
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.lastSentAt = lastSentAt;
        this.failedAttempts = 0;
    }

    public void recordFailedAttempt() {
        failedAttempts++;
    }
}
