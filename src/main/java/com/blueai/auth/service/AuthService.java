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
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            JwtProperties jwtProperties
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
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
        return issueSession(user, UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw invalidCredentials();
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(this::invalidCredentials);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        ensureActive(user);
        return issueSession(user, UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AuthResponse refresh(String rawRefreshToken) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_REFRESH_TOKEN",
                        "Refresh token is invalid."
                ));

        if (current.getRevokedAt() != null) {
            refreshTokenRepository.revokeFamily(current.getUser().getId(), current.getFamilyId(), now);
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "REFRESH_TOKEN_REUSED",
                    "Refresh token reuse detected; this session has been revoked."
            );
        }
        if (current.isExpired(now)) {
            current.revoke(now, null);
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "REFRESH_TOKEN_EXPIRED",
                    "Refresh token has expired."
            );
        }

        User user = current.getUser();
        ensureActive(user);
        SessionTokens next = createSessionTokens(user, current.getFamilyId());
        current.revoke(now, next.refreshTokenEntity().getId());
        refreshTokenRepository.save(current);
        return next.response();
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByTokenHash(hashToken(rawRefreshToken))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(
                        token.getUser().getId(),
                        token.getFamilyId(),
                        Instant.now()
                ));
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        ensureActive(user);
        return UserResponse.from(user);
    }

    private AuthResponse issueSession(User user, String familyId) {
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
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .audience(List.of(jwtProperties.getAudience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId())
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail())
                .claim("roles", List.of(user.getRole().name()))
                .claim("type", "access")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt);
    }

    private void ensureActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
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
