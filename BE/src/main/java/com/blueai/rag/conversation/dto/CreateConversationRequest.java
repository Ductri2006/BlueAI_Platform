package com.blueai.rag.conversation.dto;

import jakarta.validation.constraints.Size;

public record CreateConversationRequest(@Size(max = 150) String title) {
}
