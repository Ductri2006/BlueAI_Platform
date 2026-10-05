package com.blueai.rag.conversation.model;

/**
 * Determines which prior work may be considered while answering in a
 * conversation. Every mode still starts with the current conversation.
 */
public enum ConversationContextMode {
    CURRENT_CONVERSATION,
    LINKED_CONVERSATIONS,
    RELEVANT_HISTORY
}
