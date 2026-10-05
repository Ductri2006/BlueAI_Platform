package com.blueai.rag.document.model;

import com.blueai.auth.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

@Entity
@Table(name = "rag_documents")
public class RagDocument {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rag_document_owner"))
    private User owner;

    @Column(name = "original_filename", length = 255, nullable = false, updatable = false)
    private String originalFilename;

    @Column(name = "storage_key", length = 512, nullable = false, unique = true, updatable = false)
    private String storageKey;

    @Column(name = "content_type", length = 100, nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "requested_chunk_size", nullable = false, updatable = false)
    private int requestedChunkSize;

    @Column(name = "requested_chunk_overlap", nullable = false, updatable = false)
    private int requestedChunkOverlap;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private DocumentStatus status;

    @Column(name = "produced_chunk_count")
    private Integer producedChunkCount;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "failure_reason", length = 1_000)
    private String failureReason;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_attempt_id", length = 36)
    private String processingAttemptId;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "purge_attempted_at")
    private Instant purgeAttemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RagDocument() {
    }

    public RagDocument(
            User owner,
            String originalFilename,
            String storageKey,
            String contentType,
            long sizeBytes,
            int requestedChunkSize,
            int requestedChunkOverlap
    ) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.requestedChunkSize = requestedChunkSize;
        this.requestedChunkOverlap = requestedChunkOverlap;
        this.status = DocumentStatus.PENDING_CHUNKING;
        this.attemptCount = 0;
    }

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }

    public void markProcessing() {
        this.status = DocumentStatus.PROCESSING;
        this.processingStartedAt = Instant.now();
        this.processingAttemptId = UUID.randomUUID().toString();
        this.attemptCount++;
        this.nextAttemptAt = null;
        this.processedAt = null;
        this.producedChunkCount = null;
        this.failureReason = null;
    }

    public void markReady(int producedChunkCount) {
        this.status = DocumentStatus.READY;
        this.producedChunkCount = producedChunkCount;
        this.processedAt = Instant.now();
        this.processingStartedAt = null;
        this.processingAttemptId = null;
        this.nextAttemptAt = null;
        this.failureReason = null;
    }

    public void markFailed(String failureReason, int maxAttempts, Duration retryDelay) {
        this.processedAt = Instant.now();
        this.failureReason = failureReason;
        this.processingStartedAt = null;
        this.processingAttemptId = null;
        if (this.attemptCount >= maxAttempts) {
            this.status = DocumentStatus.FAILED;
            this.nextAttemptAt = null;
        } else {
            this.status = DocumentStatus.PENDING_CHUNKING;
            this.nextAttemptAt = Instant.now().plus(retryDelay);
        }
    }

    /**
     * Puts a document that was claimed but never reported back into the queue, so a worker
     * that died mid-processing cannot strand the row in PROCESSING forever.
     */
    public void markPendingAgain(String reason, int maxAttempts, Duration retryDelay) {
        this.failureReason = reason;
        this.processingStartedAt = null;
        this.processingAttemptId = null;
        this.processedAt = null;
        this.producedChunkCount = null;
        if (this.attemptCount >= maxAttempts) {
            this.status = DocumentStatus.FAILED;
            this.processedAt = Instant.now();
            this.nextAttemptAt = null;
        } else {
            this.status = DocumentStatus.PENDING_CHUNKING;
            this.nextAttemptAt = Instant.now().plus(retryDelay);
        }
    }

    public void softDelete() {
        if (this.deletedAt == null) {
            this.deletedAt = Instant.now();
        }
        this.processingAttemptId = null;
        this.processingStartedAt = null;
        this.nextAttemptAt = null;
    }

    public void markPurgeAttempted(Instant attemptedAt) {
        this.purgeAttemptedAt = attemptedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public String getId() {
        return id;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getOwnerId() {
        return owner.getId();
    }

    public User getOwner() {
        return owner;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getRequestedChunkSize() {
        return requestedChunkSize;
    }

    public int getRequestedChunkOverlap() {
        return requestedChunkOverlap;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public Integer getProducedChunkCount() {
        return producedChunkCount;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getProcessingAttemptId() {
        return processingAttemptId;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getPurgeAttemptedAt() {
        return purgeAttemptedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
