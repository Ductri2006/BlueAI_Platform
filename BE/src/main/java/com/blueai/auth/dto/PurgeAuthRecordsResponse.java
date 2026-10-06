package com.blueai.auth.dto;

public record PurgeAuthRecordsResponse(
        int resetTokensPurged,
        int refreshTokensPurged,
        int auditLogsPurged
) {
}
