package com.blueai.auth.service;

/** Delivers a password-reset link without exposing the raw token to the API. */
public interface PasswordResetDelivery {
    void deliver(String recipientEmail, String rawToken);
}
