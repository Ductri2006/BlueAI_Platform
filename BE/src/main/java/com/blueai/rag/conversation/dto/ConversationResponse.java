package com.blueai.rag.conversation.dto;

import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationContextMode;

import java.time.Instant;

public record ConversationResponse(
        String id,
        String title,
        ConversationContextMode contextMode,
        Instant createdAt,
        Instant updatedAt
) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getContextMode(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
