package com.blueai.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record PurgeAuthRecordsRequest(
        @Min(30) @Max(3650) int retentionDays
) {
}
