package com.blueai.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminSetPasswordRequest(
        @NotBlank @Size(min = 8, max = 72) String newPassword
) {
}
