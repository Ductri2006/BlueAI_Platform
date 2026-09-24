package com.blueai.rag.conversation.dto;

import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationContextMode;

import java.util.List;

public record ConversationContextSettingsResponse(
        ConversationContextMode mode,
        List<ConversationLinkResponse> links
) {
    public static ConversationContextSettingsResponse from(
            Conversation conversation,
            List<ConversationLinkResponse> links
    ) {
        return new ConversationContextSettingsResponse(conversation.getContextMode(), links);
    }
}
