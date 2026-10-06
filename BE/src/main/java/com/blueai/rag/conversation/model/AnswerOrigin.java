package com.blueai.rag.conversation.model;

/** Server-produced provenance; a Dialog reply is not a KB citation or an LLM completion. */
public enum AnswerOrigin {
    CORE,
    DIALOG,
    DIALOG_CONTEXT_CONFIRMATION
}
