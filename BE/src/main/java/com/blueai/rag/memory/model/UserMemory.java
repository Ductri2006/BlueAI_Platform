package com.blueai.rag.memory.model;

import com.blueai.auth.model.User;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "rag_user_memories",
        uniqueConstraints = @UniqueConstraint(name = "uk_rag_memory_owner_key", columnNames = {"owner_id", "memory_key"}),
        indexes = {
                @jakarta.persistence.Index(name = "idx_rag_memory_owner_status_updated", columnList = "owner_id,status,updated_at"),
                @jakarta.persistence.Index(name = "idx_rag_memory_conversation", columnList = "conversation_id")
        }
)
public class UserMemory {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rag_memory_owner"))
    private User owner;

    @Column(name = "memory_key", length = 100, nullable = false, updatable = false)
    private String memoryKey;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false, columnDefinition = "varchar(20) default 'USER'")
    private MemoryScope scope = MemoryScope.USER;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", foreignKey = @ForeignKey(name = "fk_rag_memory_conversation"))
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_message_id", foreignKey = @ForeignKey(name = "fk_rag_memory_source_message"))
    private ConversationMessage sourceMessage;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false, columnDefinition = "varchar(20) default 'CONFIRMED'")
    private MemoryStatus status = MemoryStatus.CONFIRMED;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "replaced_by_memory_key", length = 100)
    private String replacedByMemoryKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserMemory() {
    }

    public UserMemory(User owner, String memoryKey, String content) {
        this(owner, memoryKey, content, MemoryScope.USER, null, null, MemoryStatus.CONFIRMED, null);
    }

    public UserMemory(
            User owner,
            String memoryKey,
            String content,
            MemoryScope scope,
            Conversation conversation,
            ConversationMessage sourceMessage,
            MemoryStatus status,
            Instant expiresAt
    ) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.memoryKey = memoryKey;
        this.content = content;
        this.scope = scope;
        this.conversation = conversation;
        this.sourceMessage = sourceMessage;
        this.status = status;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
        if (scope == null) scope = MemoryScope.USER;
        if (status == null) status = MemoryStatus.CONFIRMED;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void updateContent(String content) {
        this.content = content;
    }

    public void update(
            String content,
            MemoryScope scope,
            Conversation conversation,
            ConversationMessage sourceMessage,
            MemoryStatus status,
            Instant expiresAt
    ) {
        this.content = content;
        this.scope = scope;
        this.conversation = conversation;
        this.sourceMessage = sourceMessage;
        this.status = status;
        this.expiresAt = expiresAt;
        this.replacedByMemoryKey = null;
    }

    public void markReplacedBy(String replacementMemoryKey) {
        this.status = MemoryStatus.REPLACED;
        this.replacedByMemoryKey = replacementMemoryKey;
    }

    public String getMemoryKey() {
        return memoryKey;
    }

    public String getId() { return id; }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public MemoryScope getScope() { return scope; }
    public Conversation getConversation() { return conversation; }
    public ConversationMessage getSourceMessage() { return sourceMessage; }
    public MemoryStatus getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getReplacedByMemoryKey() { return replacedByMemoryKey; }
}
