package com.blueai.rag.chat.dto;

import com.blueai.rag.conversation.dto.ConversationMessageResponse;

import java.util.List;

public record RagAnswerResponse(
        ConversationMessageResponse userMessage,
        ConversationMessageResponse assistantMessage,
        List<RagSourceResponse> sources
) {
}
