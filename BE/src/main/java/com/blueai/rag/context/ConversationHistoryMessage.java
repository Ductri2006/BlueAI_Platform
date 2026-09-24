package com.blueai.rag.context;

import com.blueai.rag.conversation.model.MessageRole;

/** A role/content pair after the context budget has been enforced. */
public record ConversationHistoryMessage(MessageRole role, String content) {
}
