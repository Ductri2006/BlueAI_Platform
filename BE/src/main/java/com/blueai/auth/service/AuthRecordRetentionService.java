package com.blueai.auth.service;

import com.blueai.auth.dto.PurgeAuthRecordsResponse;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class AuthRecordRetentionService {

    private final PasswordResetTokenRepository resetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthAuditLogService auditLogService;

    public AuthRecordRetentionService(
            PasswordResetTokenRepository resetTokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            AuthAuditLogService auditLogService
    ) {
        this.resetTokenRepository = resetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public PurgeAuthRecordsResponse purge(int retentionDays, String administratorId) {
        if (retentionDays < 30 || retentionDays > 3650) {
            throw new com.blueai.auth.exception.ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_RETENTION_POLICY",
                    "Retention must be between 30 and 3650 days."
            );
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        // Match password mutation lock order: refresh tokens before reset tokens.
        int refreshTokens = refreshTokenRepository.purgeBefore(cutoff);
        int resetTokens = resetTokenRepository.purgeBefore(cutoff);
        int auditLogs = auditLogService.purgeBefore(cutoff);
        auditLogService.recordInTransaction("AUTH_RECORDS_PURGED", "SUCCESS", administratorId, null);
        return new PurgeAuthRecordsResponse(resetTokens, refreshTokens, auditLogs);
    }
}
