package com.blueai.rag.memory.dto;

import com.blueai.rag.memory.model.MemoryScope;
import com.blueai.rag.memory.model.MemoryStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record UpsertUserMemoryRequest(
        @NotBlank @Size(max = 4_000) String content,
        MemoryScope scope,
        @Size(max = 36) String conversationId,
        @Size(max = 36) String sourceMessageId,
        Instant expiresAt,
        MemoryStatus status,
        @Size(max = 100) String replacesMemoryKey
) {
}
