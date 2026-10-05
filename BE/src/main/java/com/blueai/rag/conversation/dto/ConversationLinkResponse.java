package com.blueai.rag.conversation.dto;

import com.blueai.rag.conversation.model.ConversationLink;

import java.time.Instant;

public record ConversationLinkResponse(
        String id,
        String sourceConversationId,
        String sourceConversationTitle,
        Instant sourceSnapshotAt,
        Instant createdAt
) {
    public static ConversationLinkResponse from(ConversationLink link) {
        return new ConversationLinkResponse(
                link.getId(),
                link.getSourceConversation().getId(),
                link.getSourceConversation().getTitle(),
                link.getSourceSnapshotAt(),
                link.getCreatedAt()
        );
    }
}
