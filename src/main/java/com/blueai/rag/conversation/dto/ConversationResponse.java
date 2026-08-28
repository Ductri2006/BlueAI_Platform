package com.blueai.rag.conversation.dto;

import com.blueai.rag.conversation.model.Conversation;

import java.time.Instant;

public record ConversationResponse(String id, String title, Instant createdAt, Instant updatedAt) {

    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getTitle(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
