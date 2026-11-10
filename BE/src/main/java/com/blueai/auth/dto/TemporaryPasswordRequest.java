package com.blueai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemporaryPasswordRequest(@NotBlank @Size(max = 72) String currentPassword) {
    @Override
    public String toString() { return "TemporaryPasswordRequest[redacted]"; }
}
