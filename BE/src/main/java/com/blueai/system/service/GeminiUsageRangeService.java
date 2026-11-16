package com.blueai.system.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.system.dto.GeminiUsageRangeReport;
import com.blueai.system.dto.GeminiUsageRangeReport.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class GeminiUsageRangeService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;

    public GeminiUsageRangeService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public GeminiUsageRangeReport report(LocalDate start, LocalDate end, int requestedPage, int pageSize) {
        if (start == null || end == null || start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= 366
                || requestedPage < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_USAGE_RANGE",
                    "Chọn khoảng ngày hợp lệ, tối đa 366 ngày; trang từ 1 và số dòng từ 1 đến 100.");
        }
        Timestamp from = Timestamp.from(start.atStartOfDay(ZONE).toInstant());
        Timestamp until = Timestamp.from(end.plusDays(1).atStartOfDay(ZONE).toInstant());
        // Both MySQL and H2 support TIMESTAMPADD. The connection and stored timestamps use UTC.
        var buckets = jdbc.query("""
                SELECT CAST(TIMESTAMPADD(HOUR, 7, recorded_at) AS DATE) AS usage_day, model,
                       CASE WHEN prompt_tokens > 200000 THEN 1 ELSE 0 END AS long_prompt,
                       COUNT(*) AS requests, SUM(prompt_tokens) AS input_tokens,
                       SUM(CASE WHEN total_tokens - prompt_tokens > completion_tokens
                           THEN total_tokens - prompt_tokens ELSE completion_tokens END) AS output_tokens
                FROM gemini_usage_events WHERE recorded_at >= ? AND recorded_at < ?
                GROUP BY CAST(TIMESTAMPADD(HOUR, 7, recorded_at) AS DATE), model,
                         CASE WHEN prompt_tokens > 200000 THEN 1 ELSE 0 END
                ORDER BY usage_day
                """, ps -> { ps.setTimestamp(1, from, utc()); ps.setTimestamp(2, until, utc()); },
                (rs, i) -> new Bucket(rs.getDate("usage_day").toLocalDate(), rs.getString("model"),
                        rs.getLong("requests"), GeminiUsagePricing.estimate(rs.getString("model"),
                        rs.getBoolean("long_prompt"), rs.getLong("requests"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"))));
        Map<LocalDate, Summary> days = new TreeMap<>();
        Map<String, Summary> models = new TreeMap<>();
        Summary total = new Summary(0, Cost.zero());
        for (Bucket bucket : buckets) {
            Summary part = new Summary(bucket.requests, bucket.cost);
            total = add(total, part);
            days.merge(bucket.date, part, GeminiUsageRangeService::add);
            models.merge(bucket.model, part, GeminiUsageRangeService::add);
        }
        List<Daily> daily = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            Summary summary = days.getOrDefault(day, new Summary(0, Cost.zero()));
            daily.add(new Daily(day, summary.requests(), summary.cost()));
        }
        long totalPages = Math.max(1, (total.requests() + pageSize - 1) / pageSize);
        int page = (int) Math.min(requestedPage, totalPages);
        List<Event> recent = jdbc.query("""
                SELECT id, model, prompt_tokens, completion_tokens, total_tokens, recorded_at
                FROM gemini_usage_events WHERE recorded_at >= ? AND recorded_at < ?
                ORDER BY recorded_at DESC, id DESC LIMIT ? OFFSET ?
                """, ps -> {
                    ps.setTimestamp(1, from, utc()); ps.setTimestamp(2, until, utc());
                    ps.setInt(3, pageSize); ps.setLong(4, (long) (page - 1) * pageSize);
                }, (rs, i) -> new Event(rs.getString("id"), rs.getString("model"),
                        rs.getTimestamp("recorded_at", utc()).toInstant(),
                        GeminiUsagePricing.estimate(rs.getString("model"), rs.getLong("prompt_tokens") > 200000,
                                1, rs.getLong("prompt_tokens"), Math.max(rs.getLong("completion_tokens"),
                                rs.getLong("total_tokens") - rs.getLong("prompt_tokens")))));
        return new GeminiUsageRangeReport(start, end, ZONE.getId(), total, daily,
                models.entrySet().stream().map(e -> new ModelUsage(e.getKey(), e.getValue().requests(), e.getValue().cost())).toList(),
                recent, page, pageSize, totalPages, Instant.now(), GeminiUsagePricing.SOURCE, GeminiUsagePricing.AS_OF);
    }

    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static Summary add(Summary a, Summary b) {
        return new Summary(a.requests() + b.requests(), a.cost().plus(b.cost()));
    }
    private record Bucket(LocalDate date, String model, long requests, Cost cost) {}
}
