package com.blueai.auth.service;

import com.blueai.auth.config.PasswordResetProperties;
import com.blueai.auth.dto.PasswordResetConfirmRequest;
import com.blueai.auth.dto.PasswordResetResponse;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.PasswordResetToken;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String GENERIC_MESSAGE = "If the account exists, a password reset link has been sent.";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthAuditLogService auditLogService;
    private final PasswordResetProperties properties;
    private final PasswordResetRequestThrottle throttle;
    private final PasswordResetDeliveryDispatcher deliveryDispatcher;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetTokenRepository tokenRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            AuthAuditLogService auditLogService,
            PasswordResetProperties properties,
            PasswordResetRequestThrottle throttle,
            PasswordResetDeliveryDispatcher deliveryDispatcher
    ) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.throttle = throttle;
        this.deliveryDispatcher = deliveryDispatcher;
    }

    @Transactional
    public PasswordResetResponse request(String email) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!throttle.allow(normalizedEmail, properties.getRequestCooldown())) {
            return new PasswordResetResponse(GENERIC_MESSAGE);
        }
        // Equalize the expensive password-hash operation for known and unknown addresses.
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);
        passwordEncoder.matches("reset-timing-equalizer", user == null
                ? "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
                : user.getPasswordHash());
        if (!properties.isEnabled()) {
            return new PasswordResetResponse(GENERIC_MESSAGE);
        }
        if (user != null && user.getStatus() == UserStatus.ACTIVE && !user.isDeleted()) {
            tokenRepository.revokeActiveForUser(user.getId());
            String rawToken = generateToken();
            PasswordResetToken token = tokenRepository.saveAndFlush(new PasswordResetToken(
                    user,
                    hashToken(rawToken),
                    Instant.now().plus(properties.getTokenTtl())
            ));
            auditLogService.recordAfterCommit("PASSWORD_RESET_REQUESTED", "SUCCESS", null, user.getId());
            scheduleDelivery(token, user, rawToken);
        }
        return new PasswordResetResponse(GENERIC_MESSAGE);
    }

    @Transactional
    public void confirm(PasswordResetConfirmRequest request) {
        Instant now = Instant.now();
        PasswordResetToken token = tokenRepository.findByTokenHash(hashToken(request.token()))
                .orElseThrow(this::invalidToken);
        if (token.isUsed() || token.isExpired(now)) {
            throw invalidToken();
        }
        User user = token.getUser();
        if (user.getStatus() != UserStatus.ACTIVE || user.isDeleted()) {
            throw invalidToken();
        }
        validatePasswordLength(request.newPassword());
        token.markUsed(now);
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        refreshTokenRepository.revokeAllForUser(user.getId(), now);
        // Keep the redeemed row for retention/audit, but invalidate every other
        // outstanding reset link after the password change.
        tokenRepository.revokeActiveForUser(user.getId());
        auditLogService.recordAfterCommit("PASSWORD_RESET_COMPLETED", "SUCCESS", null, user.getId());
    }

    private ApiException invalidToken() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD_RESET_TOKEN", "The password reset link is invalid or expired.");
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "Password must not exceed 72 UTF-8 bytes.");
        }
    }

    private String generateToken() {
        byte[] value = new byte[48];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private void scheduleDelivery(PasswordResetToken token, User user, String rawToken) {
        TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliveryDispatcher.dispatch(token.getId(), user.getId(), user.getEmail(), rawToken);
            }
        });
    }

    private String hashToken(String rawToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
