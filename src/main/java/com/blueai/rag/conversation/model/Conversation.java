package com.blueai.rag.conversation.model;

import com.blueai.auth.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "rag_conversations",
        indexes = @Index(name = "idx_rag_conversation_owner_updated", columnList = "owner_id,updated_at")
)
public class Conversation {

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
