package com.blueai.rag.context;

import com.blueai.rag.memory.model.UserMemory;

import java.util.List;

public record ResolvedConversationContext(
        String retrievalQuery,
        List<ConversationHistoryMessage> recentHistory,
        String conversationSummary,
        List<UserMemory> memories,
        List<ConversationContextFragment> relatedContexts,
        List<ConversationContextSource> sources,
        boolean contextRecall
) {
}
