package com.blueai.rag.conversation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "rag_conversation_summaries")
public class ConversationSummary {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "conversation_id",
            nullable = false,
            unique = true,
            foreignKey = @ForeignKey(name = "fk_rag_summary_conversation")
    )
    private Conversation conversation;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "covered_message_count", nullable = false)
    private int coveredMessageCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ConversationSummary() {
    }

    public ConversationSummary(Conversation conversation, String content, int coveredMessageCount) {
        this.id = UUID.randomUUID().toString();
        this.conversation = conversation;
        this.content = content;
        this.coveredMessageCount = coveredMessageCount;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void update(String content, int coveredMessageCount) {
        this.content = content;
        this.coveredMessageCount = coveredMessageCount;
    }

    public String getContent() {
        return content;
    }

    public int getCoveredMessageCount() {
        return coveredMessageCount;
    }
}
