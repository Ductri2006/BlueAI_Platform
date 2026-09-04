package com.blueai.rag.document.dto;

import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.model.RagDocument;

import java.time.Instant;

public record DocumentResponse(
        String id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        int requestedChunkSize,
        int requestedChunkOverlap,
        DocumentStatus status,
        Integer producedChunkCount,
        Instant processingStartedAt,
        Instant processedAt,
        String failureReason,
        Instant createdAt
) {
    public static DocumentResponse from(RagDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getRequestedChunkSize(),
                document.getRequestedChunkOverlap(),
                document.getStatus(),
                document.getProducedChunkCount(),
                document.getProcessingStartedAt(),
                document.getProcessedAt(),
                document.getFailureReason(),
                document.getCreatedAt()
        );
    }
}
