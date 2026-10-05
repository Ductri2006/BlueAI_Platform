package com.blueai.rag.conversation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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

/**
 * A user-approved, one-way reference from a target conversation to a frozen
 * snapshot of another conversation. Snapshotting prevents later source edits
 * from silently changing the context of an existing workstream.
 */
@Entity
@Table(
        name = "rag_conversation_links",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_rag_conversation_link_target_source",
                columnNames = {"target_conversation_id", "source_conversation_id"}
        ),
        indexes = {
                @Index(name = "idx_rag_conversation_link_target", columnList = "target_conversation_id"),
                @Index(name = "idx_rag_conversation_link_source", columnList = "source_conversation_id")
        }
)
public class ConversationLink {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "target_conversation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_rag_conversation_link_target")
    )
    private Conversation targetConversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "source_conversation_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_rag_conversation_link_source")
    )
    private Conversation sourceConversation;

    @Column(name = "source_snapshot_message_id", length = 36)
    private String sourceSnapshotMessageId;

    @Lob
    @Column(name = "source_snapshot_content", nullable = false, columnDefinition = "LONGTEXT")
    private String sourceSnapshotContent;

    @Column(name = "source_snapshot_at", nullable = false)
    private Instant sourceSnapshotAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationLink() {
    }

    public ConversationLink(
            Conversation targetConversation,
            Conversation sourceConversation,
            String sourceSnapshotMessageId,
            String sourceSnapshotContent
    ) {
        this.id = UUID.randomUUID().toString();
        this.targetConversation = targetConversation;
        this.sourceConversation = sourceConversation;
        refreshSnapshot(sourceSnapshotMessageId, sourceSnapshotContent);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (sourceSnapshotAt == null) {
            sourceSnapshotAt = now;
        }
        createdAt = now;
    }

    public void refreshSnapshot(String sourceSnapshotMessageId, String sourceSnapshotContent) {
        this.sourceSnapshotMessageId = sourceSnapshotMessageId;
        this.sourceSnapshotContent = sourceSnapshotContent;
        this.sourceSnapshotAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public Conversation getTargetConversation() {
        return targetConversation;
    }

    public Conversation getSourceConversation() {
        return sourceConversation;
    }

    public String getSourceSnapshotMessageId() {
        return sourceSnapshotMessageId;
    }

    public String getSourceSnapshotContent() {
        return sourceSnapshotContent;
    }

    public Instant getSourceSnapshotAt() {
        return sourceSnapshotAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
