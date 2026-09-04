package com.blueai.rag.chat.dto;

public record RagSourceResponse(
        String documentId,
        String filename,
        String chunkId,
        Double score,
        String excerpt,
        String sourceType,
        String approvalStatus
) {
}
