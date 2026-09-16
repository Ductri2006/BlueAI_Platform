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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "studio_bot_releases",
        indexes = @Index(name = "idx_studio_release_bot_created", columnList = "bot_id,created_at"),
        uniqueConstraints = @UniqueConstraint(name = "uk_studio_release_revision", columnNames = {"bot_id", "revision"})
)
public class BotRelease {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "bot_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_release_bot"))
    private Bot bot;

    @Column(nullable = false)
    private long revision;

    @Lob
    @Column(name = "configuration_snapshot", nullable = false, columnDefinition = "longtext")
    private String configurationSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BotRelease() {
    }

    public BotRelease(Bot bot, long revision, String configurationSnapshot) {
        this.id = UUID.randomUUID().toString();
        this.bot = bot;
        this.revision = revision;
        this.configurationSnapshot = configurationSnapshot;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public String getId() { return id; }
    public Bot getBot() { return bot; }
    public long getRevision() { return revision; }
    public String getConfigurationSnapshot() { return configurationSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
