package com.blueai.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_audit_logs")
public class AuthAuditLog {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @Column(nullable = false, length = 80, updatable = false)
    private String action;

    @Column(nullable = false, length = 20, updatable = false)
    private String outcome;

    @Column(name = "actor_user_id", length = 36, updatable = false)
    private String actorUserId;

    @Column(name = "target_user_id", length = 36, updatable = false)
    private String targetUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuthAuditLog() {
    }

    public AuthAuditLog(String action, String outcome, String actorUserId, String targetUserId) {
        this.id = UUID.randomUUID().toString();
        this.action = action;
        this.outcome = outcome;
        this.actorUserId = actorUserId;
        this.targetUserId = targetUserId;
    }

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID().toString();
        createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getAction() { return action; }
    public String getOutcome() { return outcome; }
    public String getActorUserId() { return actorUserId; }
    public String getTargetUserId() { return targetUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
