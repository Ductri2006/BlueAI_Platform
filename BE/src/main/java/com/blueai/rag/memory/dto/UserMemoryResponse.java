package com.blueai.rag.memory.dto;

import com.blueai.rag.memory.model.UserMemory;
import com.blueai.rag.memory.model.MemoryScope;
import com.blueai.rag.memory.model.MemoryStatus;

import java.time.Instant;

public record UserMemoryResponse(
        String key,
        String content,
        MemoryScope scope,
        MemoryStatus status,
        String conversationId,
        String sourceMessageId,
        Instant expiresAt,
        String replacedByMemoryKey,
        Instant createdAt,
        Instant updatedAt
) {

    public static UserMemoryResponse from(UserMemory memory) {
        return new UserMemoryResponse(
                memory.getMemoryKey(),
                memory.getContent(),
                memory.getScope(),
                memory.getStatus(),
                memory.getConversation() == null ? null : memory.getConversation().getId(),
                memory.getSourceMessage() == null ? null : memory.getSourceMessage().getId(),
                memory.getExpiresAt(),
                memory.getReplacedByMemoryKey(),
                memory.getCreatedAt(),
                memory.getUpdatedAt()
        );
    }
}
