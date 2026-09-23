package com.blueai.rag.conversation.model;

import com.blueai.rag.chat.dto.ConversationContextSourceResponse;
import com.blueai.rag.chat.dto.RagSourceResponse;

import java.util.List;

/** Immutable evidence snapshot captured when an answer is generated. */
public record MessageEvidence(
        List<RagSourceResponse> sources,
        List<ConversationContextSourceResponse> contextSources
) {
    public MessageEvidence {
        sources = sources == null ? List.of() : List.copyOf(sources);
        contextSources = contextSources == null ? List.of() : List.copyOf(contextSources);
    }
}
