package com.blueai.rag.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserMessageRequest(@NotBlank @Size(max = 20_000) String content) {
}
