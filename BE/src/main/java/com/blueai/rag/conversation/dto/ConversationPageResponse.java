package com.blueai.rag.conversation.dto;

import java.util.List;

public record ConversationPageResponse(
        List<ConversationResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
}
