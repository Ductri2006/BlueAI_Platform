package com.blueai.rag.memory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertUserMemoryRequest(@NotBlank @Size(max = 4_000) String content) {
}
