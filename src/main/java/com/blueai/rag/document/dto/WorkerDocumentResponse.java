package com.blueai.rag.document.dto;

import com.blueai.rag.document.model.RagDocument;

public record WorkerDocumentResponse(
        String id,
        String storageKey,
        String originalFilename,
        String contentType,
        long sizeBytes,
        int requestedChunkSize,
        int requestedChunkOverlap
) {
    public static WorkerDocumentResponse from(RagDocument document) {
        return new WorkerDocumentResponse(
                document.getId(),
                document.getStorageKey(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getSizeBytes(),
                document.getRequestedChunkSize(),
                document.getRequestedChunkOverlap()
        );
    }
}
