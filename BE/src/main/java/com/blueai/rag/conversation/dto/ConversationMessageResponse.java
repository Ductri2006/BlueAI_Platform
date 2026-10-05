package com.blueai.rag.conversation.dto;

import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.conversation.model.MessageEvidence;

import java.time.Instant;

public record ConversationMessageResponse(
        String id, MessageRole role, String content, Instant createdAt, MessageEvidence evidence
) {

    public static ConversationMessageResponse from(ConversationMessage message) {
        return new ConversationMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt(),
                message.getEvidence()
        );
    }
}
