package com.blueai.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_users")
public class User {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(length = 190, nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "full_name", length = 100, nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    @Column(name = "active_session_id", length = 36)
    private String activeSessionId;

    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @Column(name = "temporary_password_expires_at")
    private Instant temporaryPasswordExpiresAt;

    @Column(name = "temporary_password_consumed_at")
    private Instant temporaryPasswordConsumedAt;

    public String getActiveSessionId() { return activeSessionId; }

    public void replaceSession(String sessionId) { this.activeSessionId = sessionId; }

    protected User() {
    }

    public User(String email, String passwordHash, String fullName) {
        this(email, passwordHash, fullName, UserRole.USER);
    }

    public User(String email, String passwordHash, String fullName, UserRole role) {
        this.id = UUID.randomUUID().toString();
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.status = UserStatus.ACTIVE;
    }

    public void changeStatus(UserStatus status) {
        if (this.status != status) {
            this.authVersion++;
        }
        this.status = status;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.authVersion++;
        this.passwordChangeRequired = false;
        this.temporaryPasswordExpiresAt = null;
        this.temporaryPasswordConsumedAt = null;
    }

    public void requirePasswordChange(Instant expiresAt) {
        this.passwordChangeRequired = true;
        this.temporaryPasswordExpiresAt = expiresAt;
        this.temporaryPasswordConsumedAt = null;
    }

    public void consumeTemporaryPassword(Instant now) {
        this.temporaryPasswordConsumedAt = now;
    }

    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public Instant getTemporaryPasswordExpiresAt() { return temporaryPasswordExpiresAt; }
    public Instant getTemporaryPasswordConsumedAt() { return temporaryPasswordConsumedAt; }

    public void softDelete(Instant when) {
        this.deletedAt = when;
        this.status = UserStatus.DISABLED;
        this.authVersion++;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void changeRole(UserRole role) {
        this.role = role;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getAuthVersion() {
        return authVersion;
    }
}
