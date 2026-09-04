package com.blueai.rag.document.dto;

import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.model.RagDocument;

import java.time.Instant;

public record DocumentUploadResponse(
        String id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        int requestedChunkSize,
        int requestedChunkOverlap,
        DocumentStatus status,
        Instant createdAt,
        boolean ingestionTriggered
) {
    public static DocumentUploadResponse from(RagDocument document) {
        return new DocumentUploadResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getRequestedChunkSize(),
                document.getRequestedChunkOverlap(),
                document.getStatus(),
                document.getCreatedAt(),
                false
        );
    }

    /** The nudge happens after the upload transaction commits, so the flag is set here. */
    public DocumentUploadResponse withIngestionTriggered(boolean triggered) {
        return new DocumentUploadResponse(
                id,
                originalFilename,
                contentType,
                sizeBytes,
                requestedChunkSize,
                requestedChunkOverlap,
                status,
                createdAt,
                triggered
        );
    }
}
