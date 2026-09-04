package com.blueai.rag.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskQuestionRequest(
        @NotBlank(message = "question is required")
        @Size(max = 8_000, message = "question must not exceed 8000 characters")
        String question
) {
}
