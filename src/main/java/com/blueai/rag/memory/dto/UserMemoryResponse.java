package com.blueai.rag.memory.dto;

import com.blueai.rag.memory.model.UserMemory;

import java.time.Instant;

public record UserMemoryResponse(String key, String content, Instant createdAt, Instant updatedAt) {

    public static UserMemoryResponse from(UserMemory memory) {
        return new UserMemoryResponse(
                memory.getMemoryKey(),
                memory.getContent(),
                memory.getCreatedAt(),
                memory.getUpdatedAt()
        );
    }
}
