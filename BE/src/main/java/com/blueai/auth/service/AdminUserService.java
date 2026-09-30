package com.blueai.auth.service;

import com.blueai.auth.dto.CreateManagedUserRequest;
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

@Service
public class AdminUserService {

    private static final int LEGACY_LIST_CAP = 1_000;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EntityManager entityManager;
    private final AuthAuditLogService auditLogService;

    public AdminUserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            EntityManager entityManager,
            AuthAuditLogService auditLogService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.entityManager = entityManager;
        this.auditLogService = auditLogService;
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
        auditLogService.recordAfterCommit("ADMIN_USER_CREATED", "SUCCESS", administratorId, user.getId());
        return response;
    }

    @Transactional
    public void setPassword(String administratorId, String userId, String newPassword) {
        if (!isCanonicalUserId(userId)) {
            auditLogService.record("ADMIN_PASSWORD_SET", "FAILURE", administratorId, null);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USER_ID", "User ID is invalid.");
        }
        User user = entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            auditLogService.record("ADMIN_PASSWORD_SET", "FAILURE", administratorId, userId);
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.");
        }
        if (user.isDeleted() || user.getStatus() != UserStatus.ACTIVE) {
            auditLogService.record("ADMIN_PASSWORD_SET", "FAILURE", administratorId, userId);
            throw new ApiException(HttpStatus.CONFLICT, "USER_NOT_ACTIVE", "Only active accounts can receive a new password.");
        }
        if (newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            auditLogService.record("ADMIN_PASSWORD_SET", "FAILURE", administratorId, userId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_TOO_LONG", "Password must not exceed 72 UTF-8 bytes.");
        }

        user.changePassword(passwordEncoder.encode(newPassword));
        entityManager.flush();
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        passwordResetTokenRepository.revokeActiveForUser(userId);
        auditLogService.recordAfterCommit("ADMIN_PASSWORD_SET", "SUCCESS", administratorId, userId);
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found."));
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
        auditLogService.recordAfterCommit("ADMIN_USER_STATUS_CHANGED", "SUCCESS", administratorId, userId);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse softDelete(String administratorId, String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found."));
        if (administratorId.equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_DELETE_SELF", "Administrators cannot delete their own account.");
        }
        if (!user.isDeleted()) {
            user.softDelete(Instant.now());
            refreshTokenRepository.revokeAllForUser(userId, Instant.now());
            auditLogService.recordAfterCommit("ADMIN_USER_DELETED", "SUCCESS", administratorId, userId);
        }
        return UserResponse.from(user);
    }
}
