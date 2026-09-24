package com.blueai.rag.context;

/** Metadata returned to the UI without exposing the stored context itself. */
public record ConversationContextSource(
        ContextSourceType type,
        String conversationId,
        String title,
        String memoryKey
) {
}
