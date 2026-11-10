package com.blueai.auth.service;

import com.blueai.auth.config.JwtProperties;
import com.blueai.auth.dto.AuthResponse;
import com.blueai.auth.dto.LoginRequest;
import com.blueai.auth.dto.RegisterRequest;
import com.blueai.auth.dto.UserResponse;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.RefreshToken;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.studio.service.WidgetRateLimiter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final AuthAuditLogService auditLogService;
    private final EntityManager entityManager;
    private final WidgetRateLimiter rateLimiter;
    private final int loginAttemptsPerMinute;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            JwtProperties jwtProperties,
            AuthAuditLogService auditLogService,
            EntityManager entityManager,
            WidgetRateLimiter rateLimiter,
            @Value("${app.auth.login-attempts-per-minute:20}") int loginAttemptsPerMinute
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.auditLogService = auditLogService;
        this.entityManager = entityManager;
        this.rateLimiter = rateLimiter;
        this.loginAttemptsPerMinute = loginAttemptsPerMinute;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        validatePasswordLength(request.password());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email is already registered.");
        }

        User user = new User(email, passwordEncoder.encode(request.password()), request.fullName().trim());
        userRepository.saveAndFlush(user);
        auditLogService.recordInTransaction("REGISTRATION", "SUCCESS", null, user.getId());
        return issueSession(user, UUID.randomUUID().toString());
    }

    // Authentication failures intentionally commit their audit, never a session.
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        rateLimiter.check("login-account:" + rateLimiter.clientKey(email), loginAttemptsPerMinute,
                Duration.ofMinutes(1), "Too many login attempts. Try again later.", "AUTH_RATE_LIMITED");
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            auditLogService.recordInTransaction("LOGIN", "FAILURE", null, null);
            throw invalidCredentials();
        }
        String verifiedPasswordHash = user.getPasswordHash();
        long verifiedAuthVersion = user.getAuthVersion();
        // Keep BCrypt outside the row lock, including for invalid passwords.
        if (!passwordEncoder.matches(request.password(), verifiedPasswordHash)) {
            auditLogService.recordInTransaction("LOGIN", "FAILURE", null, user.getId());
            throw invalidCredentials();
        }
        // Refresh bypasses the persistence-context snapshot. Keep the lock until
        // the session insert commits so an admin reset cannot revoke too early.
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (verifiedAuthVersion != user.getAuthVersion()
                || !verifiedPasswordHash.equals(user.getPasswordHash())) {
            auditLogService.recordInTransaction("LOGIN", "FAILURE", null, user.getId());
            throw invalidCredentials();
        }
        requireUsableTemporaryPassword(user);
        try {
            ensureActive(user);
        } catch (ApiException exception) {
            auditLogService.recordInTransaction("LOGIN", "FAILURE", null, user.getId());
            throw exception;
        }
        if (user.isPasswordChangeRequired()) user.consumeTemporaryPassword(Instant.now());
        auditLogService.recordInTransaction("LOGIN", "SUCCESS", user.getId(), user.getId());
        return issueSession(user, UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        String tokenHash = hashToken(rawRefreshToken);
        User user = lockRefreshTokenOwner(tokenHash)
                .orElseThrow(this::invalidRefreshToken);
        Instant now = Instant.now();
        RefreshToken current = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(this::invalidRefreshToken);
        // Also bypass a token loaded earlier by a surrounding transaction.
        entityManager.refresh(current, LockModeType.PESSIMISTIC_WRITE);

        // A live legacy token with no active family must reauthenticate. Previously
        // revoked tokens after logout retain the existing reuse response/audit.
        if (!current.getFamilyId().equals(user.getActiveSessionId())
                && (user.getActiveSessionId() != null || current.getRevokedAt() == null)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REPLACED",
                    "Tài khoản đã đăng nhập trên thiết bị khác. Vui lòng đăng nhập lại.");
        }

        if (current.getRevokedAt() != null) {
            refreshTokenRepository.revokeFamily(current.getUser().getId(), current.getFamilyId(), now);
            if (current.getFamilyId().equals(user.getActiveSessionId())) {
                user.replaceSession(null);
                userRepository.saveAndFlush(user);
            }
            auditLogService.recordInTransaction("REFRESH_TOKEN_REUSE", "FAILURE", current.getUser().getId(), current.getUser().getId());
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "REFRESH_TOKEN_REUSED",
                    "Refresh token reuse detected; this session has been revoked."
            );
        }
        if (current.isExpired(now)) {
            current.revoke(now, null);
            auditLogService.recordInTransaction("REFRESH", "FAILURE", current.getUser().getId(), current.getUser().getId());
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "REFRESH_TOKEN_EXPIRED",
                    "Refresh token has expired."
            );
        }

        try {
            ensureActive(user);
            requirePasswordChangeNotExpired(user);
        } catch (ApiException exception) {
            auditLogService.recordInTransaction("REFRESH", "FAILURE", user.getId(), user.getId());
            throw exception;
        }
        SessionTokens next = createSessionTokens(user, current.getFamilyId());
        current.revoke(now, next.refreshTokenEntity().getId());
        refreshTokenRepository.save(current);
        auditLogService.recordInTransaction("REFRESH", "SUCCESS", user.getId(), user.getId());
        return next.response();
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        String tokenHash = hashToken(rawRefreshToken);
        Optional<User> lockedOwner = lockRefreshTokenOwner(tokenHash);
        if (lockedOwner.isEmpty()) {
            // Unauthenticated junk must not generate unbounded audit writes.
            return;
        }
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> {
                    String userId = token.getUser().getId();
                    int revoked = refreshTokenRepository.revokeFamily(userId, token.getFamilyId(), Instant.now());
                    User owner = entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
                    if (token.getFamilyId().equals(owner.getActiveSessionId())) {
                        owner.replaceSession(null);
                        userRepository.saveAndFlush(owner);
                    }
                    if (revoked > 0) {
                        auditLogService.recordInTransaction("LOGOUT", "SUCCESS", userId, userId);
                    }
                });
    }

    private Optional<User> lockRefreshTokenOwner(String tokenHash) {
        return refreshTokenRepository.findOwnerIdByTokenHash(tokenHash).flatMap(ownerId -> {
            User owner = entityManager.find(User.class, ownerId, LockModeType.PESSIMISTIC_WRITE);
            if (owner == null) {
                return Optional.empty();
            }
            // A managed owner may predate a concurrently committed password reset.
            entityManager.refresh(owner, LockModeType.PESSIMISTIC_WRITE);
            return Optional.of(owner);
        });
    }

    private ApiException invalidRefreshToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid.");
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        ensureActive(user);
        return UserResponse.from(user);
    }

    @Transactional
    public void changePassword(String userId, String sessionId, long authVersion, String currentPassword, String newPassword) {
        rateLimiter.check("password-change:" + rateLimiter.clientKey(userId), 5,
                Duration.ofMinutes(1), "Too many password change attempts. Try again later.", "AUTH_RATE_LIMITED");
        validatePasswordLength(newPassword);
        User user = entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) throw invalidCredentials();
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        ensureActive(user);
        if (!java.util.Objects.equals(sessionId, user.getActiveSessionId()) || authVersion != user.getAuthVersion()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REPLACED", "Vui lòng đăng nhập lại.");
        }
        requirePasswordChangeNotExpired(user);
        if (!user.isPasswordChangeRequired()
                && (currentPassword == null || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "Mật khẩu hiện tại chưa đúng.");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "Mật khẩu mới phải khác mật khẩu đang dùng.");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        user.replaceSession(null);
        entityManager.flush();
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        auditLogService.recordInTransaction("PASSWORD_CHANGED", "SUCCESS", userId, userId);
    }

    private void requireUsableTemporaryPassword(User user) {
        requirePasswordChangeNotExpired(user);
        if (user.isPasswordChangeRequired() && user.getTemporaryPasswordConsumedAt() != null) throw invalidCredentials();
    }

    private void requirePasswordChangeNotExpired(User user) {
        if (user.isPasswordChangeRequired() && (user.getTemporaryPasswordExpiresAt() == null
                || !user.getTemporaryPasswordExpiresAt().isAfter(Instant.now()))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TEMPORARY_PASSWORD_EXPIRED",
                    "Mật khẩu tạm đã hết hạn. Vui lòng liên hệ admin để cấp lại.");
        }
    }

    private AuthResponse issueSession(User user, String familyId) {
        // The caller holds the user row lock (login) or owns the new row (register).
        // Revoke before issuing the new family, including sessions from older releases.
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
        user = entityManager.find(User.class, user.getId(), LockModeType.PESSIMISTIC_WRITE);
        user.replaceSession(familyId);
        userRepository.saveAndFlush(user);
        return createSessionTokens(user, familyId).response();
    }

    private SessionTokens createSessionTokens(User user, String familyId) {
        String rawRefreshToken = generateRefreshToken();
        RefreshToken refreshToken = new RefreshToken(
                user,
                hashToken(rawRefreshToken),
                familyId,
                Instant.now().plus(jwtProperties.getRefreshTokenTtl())
        );
        refreshTokenRepository.save(refreshToken);

        AccessToken accessToken = createAccessToken(user);
        long expiresIn = Math.max(0, Duration.between(Instant.now(), accessToken.expiresAt()).toSeconds());
        AuthResponse response = new AuthResponse(
                accessToken.value(),
                rawRefreshToken,
                "Bearer",
                expiresIn,
                UserResponse.from(user)
        );
        return new SessionTokens(refreshToken, response);
    }

    private AccessToken createAccessToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(jwtProperties.getAccessTokenTtl());
        if (user.isPasswordChangeRequired() && user.getTemporaryPasswordExpiresAt().isBefore(expiresAt)) {
            expiresAt = user.getTemporaryPasswordExpiresAt();
        }
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId())
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of(user.isPasswordChangeRequired() ? "PASSWORD_CHANGE" : user.getRole().name()))
                .claim("passwordChangeRequired", user.isPasswordChangeRequired())
                .claim("authVersion", user.getAuthVersion())
                .claim("sessionId", user.getActiveSessionId())
                .claim("type", "access")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE || user.isDeleted()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled.");
        }
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is invalid.");
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validatePasswordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_TOO_LONG",
                    "Password must not exceed 72 UTF-8 bytes."
            );
        }
    }

    private String generateRefreshToken() {
        byte[] value = new byte[64];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private record SessionTokens(RefreshToken refreshTokenEntity, AuthResponse response) {
    }

    private record AccessToken(String value, Instant expiresAt) {
    }
}
