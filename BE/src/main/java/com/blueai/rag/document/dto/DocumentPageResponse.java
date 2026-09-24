package com.blueai.rag.document.dto;

import java.util.List;

public record DocumentPageResponse(
        List<DocumentResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {
}
