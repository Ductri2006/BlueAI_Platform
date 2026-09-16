package com.blueai.studio.model;

import com.blueai.auth.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "studio_bots",
        indexes = @Index(name = "idx_studio_bot_owner_updated", columnList = "owner_id,updated_at"),
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_studio_bot_owner_name", columnNames = {"owner_id", "name"}),
                @UniqueConstraint(name = "uk_studio_bot_public_key", columnNames = "public_key")
        }
)
public class Bot {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_bot_owner"))
    private User owner;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 20, nullable = false)
    private String locale;

    @Column(length = 60, nullable = false)
    private String timezone;

    @Column(name = "blue_ai_core_enabled")
    private Boolean blueAiCoreEnabled;

    @Column(name = "custom_instructions", length = 2000)
    private String customInstructions;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private BotStatus status;

    @Column(name = "public_key", length = 36, nullable = false, updatable = false)
    private String publicKey;

    @Column(name = "draft_revision", nullable = false)
    private long draftRevision;

    @Column(name = "published_release_id", length = 36)
    private String publishedReleaseId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected Bot() {
    }

    public Bot(User owner, String name, String description, String locale, String timezone) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.name = name;
        this.description = description;
        this.locale = locale;
        this.timezone = timezone;
        this.blueAiCoreEnabled = true;
        this.status = BotStatus.DRAFT;
        this.publicKey = UUID.randomUUID().toString();
        this.draftRevision = 1;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID().toString();
        if (publicKey == null) publicKey = UUID.randomUUID().toString();
        if (blueAiCoreEnabled == null) blueAiCoreEnabled = true;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void updateMetadata(String name, String description, String locale, String timezone) {
        this.name = name;
        this.description = description;
        this.locale = locale;
        this.timezone = timezone;
    }

    public void updateCustomization(boolean blueAiCoreEnabled, String customInstructions) {
        this.blueAiCoreEnabled = blueAiCoreEnabled;
        this.customInstructions = customInstructions;
    }

    public void advanceDraftRevision() {
        draftRevision++;
    }

    public void publish(String releaseId) {
        this.publishedReleaseId = releaseId;
        this.status = BotStatus.PUBLISHED;
    }

    public String getId() { return id; }
    public User getOwner() { return owner; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLocale() { return locale; }
    public String getTimezone() { return timezone; }
    public boolean isBlueAiCoreEnabled() { return blueAiCoreEnabled == null || blueAiCoreEnabled; }
    public String getCustomInstructions() { return customInstructions; }
    public BotStatus getStatus() { return status; }
    public String getPublicKey() { return publicKey; }
    public long getDraftRevision() { return draftRevision; }
    public String getPublishedReleaseId() { return publishedReleaseId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
