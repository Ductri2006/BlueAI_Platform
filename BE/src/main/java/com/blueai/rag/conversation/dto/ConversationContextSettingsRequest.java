package com.blueai.rag.conversation.dto;

import com.blueai.rag.conversation.model.ConversationContextMode;
import jakarta.validation.constraints.NotNull;

public record ConversationContextSettingsRequest(@NotNull ConversationContextMode mode) {
}
