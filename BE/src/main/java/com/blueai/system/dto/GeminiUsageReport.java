package com.blueai.system.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record GeminiUsageReport(
        Totals last24Hours,
        Totals last7Days,
        Totals lifetime,
        List<DailyUsage> daily,
        List<UsageEvent> recent,
        Instant generatedAt
) {
    public record Totals(long requests, long promptTokens, long completionTokens, long totalTokens) {
    }

    public record DailyUsage(LocalDate date, long requests, long promptTokens, long completionTokens, long totalTokens) {
    }

    public record UsageEvent(String id, String model, long promptTokens, long completionTokens,
                             long totalTokens, Instant recordedAt) {
    }
}
