package com.blueai.system.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Monetary estimates use standard paid text pricing, not the provider's invoice. */
public record GeminiUsageRangeReport(
        LocalDate startDate, LocalDate endDate, String timezone,
        Summary totals, List<Daily> daily, List<ModelUsage> models, List<Event> recent,
        int page, int pageSize, long totalPages, Instant generatedAt,
        String pricingSource, LocalDate pricingAsOf
) {
    public record Cost(BigDecimal inputUsd, BigDecimal outputUsd, BigDecimal totalUsd, long unpricedRequests) {
        public static Cost zero() { return new Cost(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0); }
        public Cost plus(Cost other) {
            return new Cost(inputUsd.add(other.inputUsd), outputUsd.add(other.outputUsd),
                    totalUsd.add(other.totalUsd), unpricedRequests + other.unpricedRequests);
        }
    }
    public record Summary(long requests, Cost cost) {}
    public record Daily(LocalDate date, long requests, Cost cost) {}
    public record ModelUsage(String model, long requests, Cost cost) {}
    public record Event(String id, String model, Instant recordedAt, Cost cost) {}
}
