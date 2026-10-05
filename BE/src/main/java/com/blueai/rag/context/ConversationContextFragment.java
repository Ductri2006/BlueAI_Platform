package com.blueai.rag.context;

public record ConversationContextFragment(
        ContextSourceType type,
        String conversationId,
        String title,
        String content,
        double relevanceScore
) {
    public ConversationContextFragment withContent(String limitedContent) {
        return new ConversationContextFragment(type, conversationId, title, limitedContent, relevanceScore);
    }
}
