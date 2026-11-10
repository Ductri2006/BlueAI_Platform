package com.blueai.auth.dto;

import java.time.Instant;

public record TemporaryPasswordResponse(String temporaryPassword, Instant expiresAt) {
    @Override
    public String toString() { return "TemporaryPasswordResponse[redacted]"; }
}
