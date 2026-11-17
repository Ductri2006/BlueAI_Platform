package com.blueai.rag.conversation.model;

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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(
        name = "rag_conversations",
        indexes = @Index(name = "idx_rag_conversation_owner_updated", columnList = "owner_id,updated_at")
)
public class Conversation {

    private static final long DATABASE_TIMESTAMP_STEP_NANOS = 1_000L;

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rag_conversation_owner"))
    private User owner;

    @Column(length = 150, nullable = false)
    private String title;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Serialized Dialog PreviewContext for the active dashboard configuration. */
    @Column(name = "dialog_state_json", columnDefinition = "longtext")
    private String dialogStateJson;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "context_mode",
            nullable = false,
            length = 32,
            columnDefinition = "varchar(32) default 'CURRENT_CONVERSATION'"
    )
    private ConversationContextMode contextMode = ConversationContextMode.CURRENT_CONVERSATION;

    protected Conversation() {
    }

    public Conversation(User owner, String title) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.title = title;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
        if (contextMode == null) {
            contextMode = ConversationContextMode.CURRENT_CONVERSATION;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    /**
     * Message rows are ordered by createdAt. MySQL/H2 persist this column at
     * microsecond precision, so a pair of messages created in one clock tick
     * must be advanced explicitly instead of relying on random UUID ordering.
     */
    Instant nextMessageTimestamp(Instant candidate) {
        Instant normalized = candidate.truncatedTo(ChronoUnit.MICROS);
        if (updatedAt != null) {
            Instant previous = updatedAt.truncatedTo(ChronoUnit.MICROS);
            if (!normalized.isAfter(previous)) {
                normalized = previous.plusNanos(DATABASE_TIMESTAMP_STEP_NANOS);
            }
        }
        updatedAt = normalized;
        return normalized;
    }

    public void updateDialogState(String dialogStateJson) {
        this.dialogStateJson = dialogStateJson;
    }

    public void updateContextMode(ConversationContextMode contextMode) {
        this.contextMode = contextMode;
    }

    public void softDelete() {
        if (deletedAt == null) {
            deletedAt = Instant.now();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() { return owner.getId(); }

    public String getTitle() {
        return title;
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

    public String getDialogStateJson() {
        return dialogStateJson;
    }

    public ConversationContextMode getContextMode() {
        return contextMode;
    }
}
