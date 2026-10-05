package com.blueai.rag.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationLinkRequest(
        @NotBlank @Size(max = 36) String sourceConversationId
) {
}
