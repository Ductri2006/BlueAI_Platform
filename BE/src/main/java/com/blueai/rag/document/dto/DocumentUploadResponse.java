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
        Instant createdAt
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
                document.getCreatedAt()
        );
    }
}
