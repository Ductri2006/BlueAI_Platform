package com.blueai.rag.conversation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "rag_conversation_messages",
        indexes = @Index(name = "idx_rag_message_conversation_created", columnList = "conversation_id,created_at")
)
public class ConversationMessage {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rag_message_conversation"))
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false, updatable = false)
    private MessageRole role;

    @Lob
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Convert(converter = MessageEvidenceConverter.class)
    @Column(name = "evidence_json", updatable = false, columnDefinition = "LONGTEXT")
    private MessageEvidence evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer_status", length = 30, nullable = false, updatable = false)
    private AnswerStatus answerStatus = AnswerStatus.ANSWERED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ConversationMessage() {
    }

    public ConversationMessage(Conversation conversation, MessageRole role, String content) {
        this(conversation, role, content, null);
    }

    public ConversationMessage(Conversation conversation, MessageRole role, String content, MessageEvidence evidence) {
        this.id = UUID.randomUUID().toString();
        this.conversation = conversation;
        this.role = role;
        this.content = content;
        this.evidence = role == MessageRole.ASSISTANT ? evidence : null;
    }

    public MessageEvidence getEvidence() {
        return evidence;
    }

    public AnswerStatus getAnswerStatus() { return answerStatus; }

    public void setAnswerStatus(AnswerStatus value) {
        this.answerStatus = value == null ? AnswerStatus.ANSWERED : value;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = conversation == null
                ? Instant.now()
                : conversation.nextMessageTimestamp(Instant.now());
    }

    public String getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
