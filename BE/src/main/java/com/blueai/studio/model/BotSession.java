package com.blueai.studio.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "studio_bot_sessions", indexes = @Index(name = "idx_studio_session_bot_updated", columnList = "bot_id,updated_at"))
public class BotSession {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "bot_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_session_bot"))
    private Bot bot;

    @Column(name = "release_id", length = 36, nullable = false)
    private String releaseId;

    @Column(name = "access_token_hash", length = 64, nullable = false)
    private String accessTokenHash;

    @Lob
    @Column(name = "state_json", nullable = false, columnDefinition = "longtext")
    private String stateJson;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "turn_count", nullable = false)
    private int turnCount;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BotSession() {
    }

    public BotSession(Bot bot, String releaseId, String accessTokenHash, String stateJson, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.bot = bot;
        this.releaseId = releaseId;
        this.accessTokenHash = accessTokenHash;
        this.stateJson = stateJson;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public void updateState(String stateJson) { this.stateJson = stateJson; }

    public void incrementTurnCount() { this.turnCount++; }

    public String getId() { return id; }
    public Bot getBot() { return bot; }
    public String getReleaseId() { return releaseId; }
    public String getAccessTokenHash() { return accessTokenHash; }
    public String getStateJson() { return stateJson; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getTurnCount() { return turnCount; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
