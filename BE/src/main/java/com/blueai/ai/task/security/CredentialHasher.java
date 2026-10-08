package com.blueai.ai.task.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** One-way hashing for machine integration credentials. */
public final class CredentialHasher {
    private CredentialHasher() {}

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
