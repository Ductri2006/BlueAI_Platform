package com.blueai.studio.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
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
        name = "studio_bot_turns",
        uniqueConstraints = @UniqueConstraint(name = "uk_studio_turn_request", columnNames = {"session_id", "request_id"})
)
public class BotTurn {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "session_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_turn_session"))
    private BotSession session;

    @Column(name = "request_id", length = 80, nullable = false)
    private String requestId;

    @Column(name = "request_digest", length = 64, nullable = false)
    private String requestDigest;

    @Lob
    @Column(name = "response_json", nullable = false, columnDefinition = "longtext")
    private String responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BotTurn() {
    }

    public BotTurn(BotSession session, String requestId, String requestDigest, String responseJson) {
        this.id = UUID.randomUUID().toString();
        this.session = session;
        this.requestId = requestId;
        this.requestDigest = requestDigest;
        this.responseJson = responseJson;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public String getId() { return id; }
    public BotSession getSession() { return session; }
    public String getRequestId() { return requestId; }
    public String getRequestDigest() { return requestDigest; }
    public String getResponseJson() { return responseJson; }
    public Instant getCreatedAt() { return createdAt; }
}
