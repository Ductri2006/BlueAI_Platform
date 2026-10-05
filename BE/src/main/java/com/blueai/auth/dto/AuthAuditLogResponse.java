package com.blueai.auth.dto;

import com.blueai.auth.model.AuthAuditLog;

import java.time.Instant;

public record AuthAuditLogResponse(
        String id,
        String action,
        String outcome,
        String actorUserId,
        String targetUserId,
        Instant createdAt
) {
    public static AuthAuditLogResponse from(AuthAuditLog log) {
        return new AuthAuditLogResponse(
                log.getId(),
                log.getAction(),
                log.getOutcome(),
                log.getActorUserId(),
                log.getTargetUserId(),
                log.getCreatedAt()
        );
    }
}
