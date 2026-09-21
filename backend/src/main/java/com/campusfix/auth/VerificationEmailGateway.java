package com.campusfix.auth;

public interface VerificationEmailGateway {

    void sendVerificationCode(String recipientEmail, String verificationCode, long validityMinutes);
}
