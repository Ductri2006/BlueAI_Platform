package com.blueai.system.service;

import com.blueai.system.dto.GeminiUsageReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class GeminiUsageService {

    private final JdbcTemplate jdbcTemplate;

    public GeminiUsageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Records only provider-reported usage; no prompt or response text is stored. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String model, long promptTokens, long completionTokens, long totalTokens) {
        if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) return;
        jdbcTemplate.update("""
                INSERT INTO gemini_usage_events
                    (id, model, prompt_tokens, completion_tokens, total_tokens, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), model == null ? "unknown" : model.substring(0, Math.min(model.length(), 120)),
                promptTokens, completionTokens, totalTokens, Timestamp.from(Instant.now()));
    }

    @Transactional(readOnly = true)
    public GeminiUsageReport report() {
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minus(java.time.Duration.ofDays(7));
        List<GeminiUsageReport.DailyUsage> daily = jdbcTemplate.query("""
                SELECT CAST(recorded_at AS DATE) AS usage_day, COUNT(*) AS requests,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens
                FROM gemini_usage_events WHERE recorded_at >= ?
                GROUP BY CAST(recorded_at AS DATE) ORDER BY usage_day
                """, (rs, rowNum) -> new GeminiUsageReport.DailyUsage(
                rs.getDate("usage_day").toLocalDate(), rs.getLong("requests"),
                rs.getLong("prompt_tokens"), rs.getLong("completion_tokens"), rs.getLong("total_tokens")
        ), Timestamp.from(sevenDaysAgo));
        List<GeminiUsageReport.UsageEvent> recent = jdbcTemplate.query("""
                SELECT id, model, prompt_tokens, completion_tokens, total_tokens, recorded_at
                FROM gemini_usage_events ORDER BY recorded_at DESC LIMIT 50
                """, (rs, rowNum) -> new GeminiUsageReport.UsageEvent(
                rs.getString("id"), rs.getString("model"), rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"), rs.getLong("total_tokens"),
                rs.getTimestamp("recorded_at").toInstant()
        ));
        return new GeminiUsageReport(
                totals(now.minus(java.time.Duration.ofHours(24))),
                totals(sevenDaysAgo),
                totals(null),
                daily, recent, now
        );
    }

    private GeminiUsageReport.Totals totals(Instant since) {
        String sql = """
                SELECT COUNT(*) AS requests,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens
                FROM gemini_usage_events
                """ + (since == null ? "" : " WHERE recorded_at >= ?");
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new GeminiUsageReport.Totals(
                rs.getLong("requests"), rs.getLong("prompt_tokens"),
                rs.getLong("completion_tokens"), rs.getLong("total_tokens")
        ), since == null ? new Object[0] : new Object[]{Timestamp.from(since)});
    }
}
