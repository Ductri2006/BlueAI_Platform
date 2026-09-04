package com.blueai.system.dto;

import java.time.Instant;

public record SystemMetricsResponse(
        long totalUsers,
        long totalConversations,
        long totalMessages,
        long totalDocuments,
        long readyDocuments,
        long pendingDocuments,
        long failedDocuments,
        boolean retrieverConfigured,
        boolean llmConfigured,
        String llmModel,
        boolean aiRuntimeReady,
        String aiRuntimeStatus,
        Instant generatedAt
) {
}
