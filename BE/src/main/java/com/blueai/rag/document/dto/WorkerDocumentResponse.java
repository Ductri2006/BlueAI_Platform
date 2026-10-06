package com.blueai.rag.document.dto;

import com.blueai.rag.document.model.RagDocument;

public record WorkerDocumentResponse(
        String id,
        String ownerId,
        String sourceType,
        String storageKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        int requestedChunkSize,
        int requestedChunkOverlap,
        String attemptId
) {
    public static WorkerDocumentResponse from(RagDocument document) {
        return new WorkerDocumentResponse(
                document.getId(),
                document.getOwnerId(),
                document.getOwner().getRole().name().equals("ADMIN") ? "ENTERPRISE_DOCUMENT" : "USER_DOCUMENT",
                document.getStorageKey(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getRequestedChunkSize(),
                document.getRequestedChunkOverlap(),
                document.getProcessingAttemptId()
        );
    }
}
