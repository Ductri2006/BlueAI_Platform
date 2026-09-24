package com.blueai.auth.service;

import com.blueai.auth.repository.PasswordResetTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordResetDeliveryStatusService {

    private final PasswordResetTokenRepository tokenRepository;
    private final AuthAuditLogService auditLogService;

    public PasswordResetDeliveryStatusService(
            PasswordResetTokenRepository tokenRepository,
            AuthAuditLogService auditLogService
    ) {
        this.tokenRepository = tokenRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void markSent(String tokenId, String userId) {
        tokenRepository.findById(tokenId).ifPresent(token -> {
            token.markDelivered();
            auditLogService.recordAfterCommit("PASSWORD_RESET_DELIVERY", "SUCCESS", null, userId);
        });
    }

    @Transactional
    public void markFailed(String tokenId, String userId) {
        tokenRepository.findById(tokenId).ifPresent(token -> {
            token.markDeliveryFailed();
            auditLogService.record("PASSWORD_RESET_DELIVERY", "FAILURE", null, userId);
        });
    }
}
