package com.blueai.auth.service;

import com.blueai.auth.dto.CreateManagedUserRequest;
import com.blueai.auth.dto.TemporaryPasswordResponse;
import com.blueai.auth.model.UserRole;
import com.blueai.studio.service.WidgetRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import com.blueai.auth.dto.PageResponse;
import com.blueai.auth.dto.UserResponse;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import java.util.UUID;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

@Service
public class AdminUserService {

    private static final int LEGACY_LIST_CAP = 1_000;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EntityManager entityManager;
    private final AuthAuditLogService auditLogService;
    private final WidgetRateLimiter rateLimiter;
    private final Duration temporaryPasswordTtl;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public AdminUserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            EntityManager entityManager,
            AuthAuditLogService auditLogService,
            WidgetRateLimiter rateLimiter,
            @Value("${app.auth.temporary-password-ttl:15m}") Duration temporaryPasswordTtl
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.entityManager = entityManager;
        this.auditLogService = auditLogService;
        this.rateLimiter = rateLimiter;
        if (temporaryPasswordTtl.isNegative() || temporaryPasswordTtl.isZero()
                || temporaryPasswordTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Temporary password lifetime must be positive and at most one day.");
        }
        this.temporaryPasswordTtl = temporaryPasswordTtl;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.searchUsers(null, null, false, PageRequest.of(0, LEGACY_LIST_CAP, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> searchUsers(String search, UserStatus status, boolean includeDeleted, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGE_REQUEST", "Page must be non-negative and size must be between 1 and 100.");
        }
        Page<User> users = userRepository.searchUsers(
                escapeLike(search),
                status,
                includeDeleted,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")))
        );
        return new PageResponse<>(users.map(UserResponse::from).getContent(), page, size, users.getTotalElements(), users.getTotalPages());
    }

    private String escapeLike(String search) {
        if (search == null || search.isBlank()) return null;
        return search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Transactional
    public UserResponse createUser(CreateManagedUserRequest request) {
        return createUser(null, request);
    }

    @Transactional
    public UserResponse createUser(String administratorId, CreateManagedUserRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "Password must not exceed 72 UTF-8 bytes.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_EXISTS", "Email is already registered.");
        }

        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                request.role()
        );
        UserResponse response = UserResponse.from(userRepository.saveAndFlush(user));
        auditLogService.recordInTransaction("ADMIN_USER_CREATED", "SUCCESS", administratorId, user.getId());
        return response;
    }

    // Validation guards precede mutation; their failure audit must still commit.
    @Transactional(noRollbackFor = ApiException.class)
    public void setPassword(String administratorId, String userId, String newPassword) {
        if (!isCanonicalUserId(userId)) {
            auditLogService.recordInTransaction("ADMIN_PASSWORD_SET", "FAILURE", administratorId, null);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID", "User ID is invalid.");
        }
        User user = lockUser(userId);
        if (user == null) {
            auditLogService.recordInTransaction("ADMIN_PASSWORD_SET", "FAILURE", administratorId, userId);
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.");
        }
        if (user.isDeleted() || user.getStatus() != UserStatus.ACTIVE) {
            auditLogService.recordInTransaction("ADMIN_PASSWORD_SET", "FAILURE", administratorId, userId);
            throw new ApiException(HttpStatus.CONFLICT, "USER_NOT_ACTIVE", "Only active accounts can receive a new password.");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            auditLogService.recordInTransaction("ADMIN_PASSWORD_SET", "FAILURE", administratorId, userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "Password must not exceed 72 UTF-8 bytes.");
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        entityManager.flush();
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        passwordResetTokenRepository.revokeActiveForUser(userId);
        auditLogService.recordInTransaction("ADMIN_PASSWORD_SET", "SUCCESS", administratorId, userId);
    }

    @Transactional
    public TemporaryPasswordResponse resetTemporaryPassword(String administratorId, String userId, String currentPassword) {
        rateLimiter.check("password-reset:" + rateLimiter.clientKey(administratorId), 5,
                Duration.ofMinutes(1), "Too many password resets. Try again later.", "AUTH_RATE_LIMITED");
        User administrator = lockUser(administratorId);
        if (administrator == null || administrator.getRole() != UserRole.ADMIN || administrator.isDeleted()
                || administrator.getStatus() != UserStatus.ACTIVE || administrator.isPasswordChangeRequired()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Tài khoản admin đang hoạt động là bắt buộc.");
        }
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(currentPassword, administrator.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "Mật khẩu admin chưa đúng.");
        }
        if (!isCanonicalUserId(userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID", "User ID is invalid.");
        }
        User target = lockUser(userId);
        if (target == null) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.");
        if (target.getRole() != UserRole.USER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_RESET_ONLY", "Chỉ được cấp mật khẩu tạm cho tài khoản User.");
        }
        byte[] random = new byte[18];
        SECURE_RANDOM.nextBytes(random);
        String temporaryPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        setPassword(administratorId, userId, temporaryPassword);
        // Token revocation clears the persistence context; reacquire the managed row.
        target = lockUser(userId);
        Instant expiresAt = Instant.now().plus(temporaryPasswordTtl);
        target.requirePasswordChange(expiresAt);
        target.replaceSession(null);
        entityManager.flush();
        return new TemporaryPasswordResponse(temporaryPassword, expiresAt);
    }

    private boolean isCanonicalUserId(String userId) {
        if (userId == null || userId.length() != 36) {
            return false;
        }
        try {
            return UUID.fromString(userId).toString().equalsIgnoreCase(userId);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Transactional
    public UserResponse updateStatus(String administratorId, String userId, UserStatus status) {
        User user = lockUser(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.");
        }
        if (user.isDeleted()) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_DELETED", "Deleted user accounts cannot be reactivated or changed.");
        }
        if (administratorId.equals(userId) && status == UserStatus.DISABLED) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF", "Administrators cannot disable their own account.");
        }
        user.changeStatus(status);
        if (status == UserStatus.DISABLED) {
            refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        }
        auditLogService.recordInTransaction("ADMIN_USER_STATUS_CHANGED", "SUCCESS", administratorId, userId);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse softDelete(String administratorId, String userId) {
        User user = lockUser(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.");
        }
        if (administratorId.equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DELETE_SELF", "Administrators cannot delete their own account.");
        }
        if (user.getRole() != UserRole.USER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DELETE_ONLY", "Chỉ được xóa tài khoản User.");
        }
        if (!user.isDeleted()) {
            user.softDelete(Instant.now());
            refreshTokenRepository.revokeAllForUser(userId, Instant.now());
            auditLogService.recordInTransaction("ADMIN_USER_DELETED", "SUCCESS", administratorId, userId);
        }
        return UserResponse.from(user);
    }

    private User lockUser(String userId) {
        User user = entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (user != null) {
            // Upgrading a preloaded entity's lock does not refresh its stale state.
            // The database lock remains held through token revocations and commit.
            entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        }
        return user;
    }
}
