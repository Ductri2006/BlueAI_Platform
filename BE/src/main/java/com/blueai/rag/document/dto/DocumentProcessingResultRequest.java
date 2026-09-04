package com.blueai.rag.document.dto;

import com.blueai.rag.document.model.DocumentStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DocumentProcessingResultRequest(
        @NotNull DocumentStatus status,
        @Min(0) Integer producedChunkCount,
        @Size(max = 1_000) String failureReason
) {
}
