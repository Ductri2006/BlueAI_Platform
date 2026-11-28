package com.blueai.rag.conversation.model;

/** Delivery outcome is independent of the origin and existence of evidence. */
public enum AnswerStatus {
    ANSWERED, NEEDS_DETAILS, INSUFFICIENT_EVIDENCE, SAFE_FALLBACK
}
