package com.blueai.ai.task.security;

import com.blueai.ai.task.model.PublishedApiPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Database-backed, per-key HTTP request quota shared by every application replica. */
@Service
public class PublishedApiAccess {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public PublishedApiAccess(JdbcTemplate jdbc, TransactionTemplate tx) { this.jdbc = jdbc; this.tx = tx; }

    public List<PublishedApiPrincipal> authenticate(String hash) {
        return jdbc.query("""
                select k.id,k.bot_id,b.owner_id from studio_bot_api_keys k
                join studio_bots b on b.id=k.bot_id join app_users u on u.id=b.owner_id
                where k.credential_hash=? and b.status='PUBLISHED'
                  and u.status='ACTIVE' and u.deleted_at is null and u.role='ADMIN'
                """, (row, index) -> new PublishedApiPrincipal(row.getString(1), row.getString(2), row.getString(3)), hash);
    }

    /** Returns seconds until retry, or zero when the request has consumed one slot. */
    public int consume(String keyId) {
        Integer result = tx.execute(status -> {
            var rows = jdbc.queryForList("select requests_per_minute,rate_window_start,rate_count from studio_bot_api_keys where id=? for update", keyId);
            if (rows.isEmpty()) return 60;
            var row = rows.getFirst();
            Instant now = jdbc.queryForObject("select current_timestamp", Timestamp.class).toInstant();
            Instant window = row.get("rate_window_start") instanceof Timestamp time ? time.toInstant() : null;
            int count = ((Number) row.get("rate_count")).intValue();
            if (window == null || !window.isAfter(now.minusSeconds(60))) { window = now; count = 0; }
            if (count >= ((Number) row.get("requests_per_minute")).intValue()) {
                return (int) Math.max(1, (window.plusSeconds(60).toEpochMilli() - now.toEpochMilli() + 999) / 1000);
            }
            jdbc.update("update studio_bot_api_keys set rate_window_start=?,rate_count=? where id=?", Timestamp.from(window), count + 1, keyId);
            return 0;
        });
        return result == null ? 60 : result;
    }
}
