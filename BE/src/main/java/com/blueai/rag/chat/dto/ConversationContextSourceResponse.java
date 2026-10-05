package com.blueai.rag.chat.dto;

import com.blueai.rag.context.ConversationContextSource;

public record ConversationContextSourceResponse(
        String type,
        String conversationId,
        String title,
        String memoryKey
) {
    public static ConversationContextSourceResponse from(ConversationContextSource source) {
        return new ConversationContextSourceResponse(
                source.type().name(),
                source.conversationId(),
                source.title(),
                source.memoryKey()
        );
    }
}
