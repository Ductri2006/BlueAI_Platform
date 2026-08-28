package com.blueai.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refresh_token_user"))
    private User user;

    @JdbcTypeCode(Types.CHAR)
    @Column(name = "token_hash", length = 64, nullable = false, unique = true, updatable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "family_id", length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String familyId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by_token_id", length = 36, columnDefinition = "char(36)")
    private String replacedByTokenId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
    }

    public RefreshToken(User user, String tokenHash, String familyId, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.user = user;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public void revoke(Instant when, String replacementTokenId) {
        this.revokedAt = when;
        this.replacedByTokenId = replacementTokenId;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public String getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
