package com.blueai.quota;

import com.blueai.auth.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Short independent transactions reserve both budgets before any provider work. */
@Service
public class AiTokenQuotaService {
    public static final Duration FIVE_HOURS = Duration.ofHours(5);
    public static final Duration SEVEN_DAYS = Duration.ofDays(7);
    private final JdbcTemplate jdbc;
    private final AiQuotaProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public AiTokenQuotaService(AiUsageDataAccess dataAccess, AiQuotaProperties properties) {
        this(dataAccess.jdbc(), properties, dataAccess.manager(), Clock.systemUTC());
    }

    AiTokenQuotaService(JdbcTemplate jdbc, AiQuotaProperties properties, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean enabled() { return properties.isEnabled(); }
    public int maxOutputTokens() { return properties.getMaxOutputTokens(); }
    public String embeddingModel() { return properties.getEmbeddingModel(); }
    public static long inputBound(String text) {
        // UTF-8 bytes plus envelope allowance are deliberately conservative, not a reported token count.
        return Math.addExact(text.getBytes(StandardCharsets.UTF_8).length, 1024L);
    }

    public Reservation reserve(String kind, long inputBound, int outputBound) {
        if (!enabled()) return null;
        if (inputBound < 0 || outputBound < 0) throw new IllegalArgumentException("Negative token reservation");
        String actor = AiUsageActor.current();
        String model = "EMBEDDING".equals(kind) ? embeddingModel() : properties.getChatModel();
        long cap = Math.addExact(inputBound, outputBound);
        return transaction.execute(ignored -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Account account = actor == null ? null : lockAccount(actor, now);
            if (account != null) {
                if (cap > properties.getFiveHourTokens() || cap > properties.getWeeklyTokens())
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "AI_REQUEST_TOO_LARGE",
                            "Ngữ cảnh câu hỏi vượt mức dành cho một yêu cầu. Hãy rút ngắn hoặc tạo hội thoại mới.");
                boolean fiveBlocked = account.fiveUsed + account.fiveReserved + cap > properties.getFiveHourTokens();
                boolean weekBlocked = account.weekUsed + account.weekReserved + cap > properties.getWeeklyTokens();
                if (fiveBlocked || weekBlocked) {
                    Instant reset = fiveBlocked ? account.fiveStart.plus(FIVE_HOURS) : now;
                    if (weekBlocked && account.weekStart.plus(SEVEN_DAYS).isAfter(reset)) reset = account.weekStart.plus(SEVEN_DAYS);
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_QUOTA_EXHAUSTED",
                            "Hạn mức AI chưa đủ cho yêu cầu này. Vui lòng chờ quota hồi phục.", seconds(now, reset));
                }
            }
            Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
            // Google's daily request counter resets at midnight Pacific, including daylight saving changes.
            Instant day = now.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate()
                    .atStartOfDay(ZoneId.of("America/Los_Angeles")).toInstant();
            boolean embedding = "EMBEDDING".equals(kind);
            long rpm = embedding ? properties.getEmbeddingRpm() : properties.getChatRpm();
            long tpm = embedding ? properties.getEmbeddingTpm() : properties.getChatTpm();
            long rpd = embedding ? properties.getEmbeddingRpd() : properties.getChatRpd();
            reserveProject(model, "DAY", day, rpd, Long.MAX_VALUE, inputBound,
                    day.atZone(ZoneId.of("America/Los_Angeles")).plusDays(1).toInstant(), now);
            reserveProject(model, "MINUTE", minute, rpm, tpm, inputBound, minute.plusSeconds(60), now);
            if (account != null && (account.fiveStart == null || account.weekStart == null)) {
                Instant five = account.fiveStart == null ? now : account.fiveStart;
                Instant week = account.weekStart == null ? now : account.weekStart;
                jdbc.update("UPDATE ai_quota_accounts SET five_start=?,week_start=? WHERE user_id=?", ts(five), ts(week), actor);
                account = new Account(five, week, account.fiveUsed, account.weekUsed, account.fiveReserved, account.weekReserved, account.fiveUncertain, account.weekUncertain);
            }
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ai_token_operations (id,user_id,operation_kind,model,status,reserved_tokens,reserved_input,"
                            + "five_start,week_start,minute_start,day_start,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, actor, kind, model, "PENDING", cap, inputBound,
                    ts(account == null ? null : account.fiveStart), ts(account == null ? null : account.weekStart),
                    ts(minute), ts(day), ts(now), ts(now.plusSeconds(300)));
            if (account != null) jdbc.update("UPDATE ai_quota_accounts SET five_reserved=five_reserved+?,week_reserved=week_reserved+? WHERE user_id=?", cap, cap, actor);
            return new Reservation(id, cap, model);
        });
    }

    private void reserveProject(String model, String kind, Instant start, long requestLimit, long tokenLimit,
                                long input, Instant reset, Instant now) {
        jdbc.update("INSERT INTO ai_project_windows(model,window_kind,window_start,requests,input_tokens) VALUES (?,?,?,0,0) ON DUPLICATE KEY UPDATE requests=requests", model, kind, ts(start));
        long[] counts = jdbc.queryForObject("SELECT requests,input_tokens FROM ai_project_windows WHERE model=? AND window_kind=? AND window_start=? FOR UPDATE",
                (rs, row) -> new long[]{rs.getLong(1), rs.getLong(2)}, model, kind, ts(start));
        if (counts[0] >= requestLimit || input > tokenLimit - counts[1])
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_PROJECT_RATE_LIMITED",
                    "Hạn mức AI dùng chung đang bận. Vui lòng thử lại sau.", seconds(now, reset));
        jdbc.update("UPDATE ai_project_windows SET requests=requests+1,input_tokens=input_tokens+? WHERE model=? AND window_kind=? AND window_start=?", input, model, kind, ts(start));
    }

    public void confirm(Reservation reservation, long prompt, long completion, long total) {
        if (reservation == null) return;
        if (prompt < 0 || completion < 0 || total < prompt || total < completion)
            throw new IllegalArgumentException("Invalid provider token usage");
        settle(reservation.id, prompt, completion, total, "CONFIRMED");
    }

    public void confirmCountedInput(Reservation reservation, long tokens) {
        if (reservation == null) return;
        if (tokens < 0) throw new IllegalArgumentException("Negative token count");
        settle(reservation.id, tokens, 0L, tokens, "COUNTED");
    }

    /** Only use when the provider definitively rejected work before generation. */
    public void release(Reservation reservation) {
        if (reservation != null) settle(reservation.id, 0L, 0L, 0L, "RELEASED");
    }

    // Ambiguous failures remain reserved briefly. A bounded estimate replaces the hold after expiry.
    // It is explicitly separate from confirmed provider usage and can later be reconciled by operation id.
    @Scheduled(fixedDelay = 60_000)
    public void reconcileExpired() {
        if (!enabled()) return;
        List<String> ids = jdbc.queryForList("SELECT id FROM ai_token_operations WHERE status='PENDING' AND expires_at<=? ORDER BY created_at LIMIT 100", String.class, ts(clock.instant()));
        ids.forEach(id -> settle(id, null, null, null, "ESTIMATED"));
    }

    private void settle(String id, Long prompt, Long completion, Long total, String status) {
        transaction.executeWithoutResult(ignored -> {
            List<String> actors = jdbc.query("SELECT user_id FROM ai_token_operations WHERE id=?", (rs, row) -> rs.getString(1), id);
            if (actors.isEmpty()) return;
            Instant now = clock.instant();
            Account account = actors.getFirst() == null ? null : lockAccount(actors.getFirst(), now);
            Operation operation = jdbc.queryForObject("SELECT * FROM ai_token_operations WHERE id=? FOR UPDATE", (rs, row) ->
                    new Operation(rs.getString("user_id"), rs.getString("model"), rs.getString("status"),
                            rs.getLong("reserved_tokens"), rs.getLong("reserved_input"), rs.getLong("total_tokens"),
                            instant(rs.getTimestamp("five_start")), instant(rs.getTimestamp("week_start")),
                            instant(rs.getTimestamp("minute_start")), instant(rs.getTimestamp("day_start"))), id);
            if (!operation.status.equals("PENDING") && !(operation.status.equals("ESTIMATED") && (status.equals("CONFIRMED") || status.equals("COUNTED")))) return;
            boolean pending = operation.status.equals("PENDING");
            long charge = status.equals("ESTIMATED") ? operation.reserved : total;
            long previous = pending ? 0 : operation.total;
            long hold = pending ? operation.reserved : 0;
            long uncertainDelta = status.equals("ESTIMATED") ? charge : pending ? 0 : -previous;
            if (account != null) {
                if (java.util.Objects.equals(account.fiveStart, operation.fiveStart)) jdbc.update("UPDATE ai_quota_accounts SET five_reserved=five_reserved-?,five_used=five_used+?,five_uncertain=five_uncertain+? WHERE user_id=?", hold, charge - previous, uncertainDelta, operation.actor);
                if (java.util.Objects.equals(account.weekStart, operation.weekStart)) jdbc.update("UPDATE ai_quota_accounts SET week_reserved=week_reserved-?,week_used=week_used+?,week_uncertain=week_uncertain+? WHERE user_id=?", hold, charge - previous, uncertainDelta, operation.actor);
            }
            // An estimate keeps the conservative input reservation. Confirmed usage releases unused TPM.
            if (!status.equals("ESTIMATED")) {
                long delta = prompt - operation.input;
                jdbc.update("UPDATE ai_project_windows SET input_tokens=GREATEST(0,input_tokens+?) WHERE model=? AND ((window_kind='MINUTE' AND window_start=?) OR (window_kind='DAY' AND window_start=?))", delta, operation.model, ts(operation.minute), ts(operation.day));
            }
            jdbc.update("UPDATE ai_token_operations SET status=?,prompt_tokens=?,completion_tokens=?,total_tokens=?,measurement=?,settled_at=? WHERE id=?", status, prompt, completion, charge,
                    status.equals("COUNTED") ? "COUNT_TOKENS" : status.equals("CONFIRMED") ? "USAGE_METADATA" : status, ts(now), id);
        });
    }

    public Snapshot snapshot(String actor) {
        if (!enabled()) return new Snapshot(false, clock.instant(), null, null);
        reconcileExpired();
        return transaction.execute(ignored -> {
            Instant now = clock.instant();
            Account account = lockAccount(actor, now);
            return new Snapshot(true, now,
                    window(account.fiveStart, FIVE_HOURS, properties.getFiveHourTokens(), account.fiveUsed, account.fiveReserved, account.fiveUncertain, now),
                    window(account.weekStart, SEVEN_DAYS, properties.getWeeklyTokens(), account.weekUsed, account.weekReserved, account.weekUncertain, now));
        });
    }

    private Window window(Instant start, Duration duration, long limit, long used, long reserved, long uncertain, Instant now) {
        // Merely reading quota never starts its recovery clock.
        Instant reset = start == null ? null : start.plus(duration);
        return new Window(limit, used, reserved, uncertain, Math.max(0, limit - used - reserved), reset, reset == null ? 0 : seconds(now, reset));
    }

    private Account lockAccount(String actor, Instant now) {
        jdbc.update("INSERT INTO ai_quota_accounts(user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id=user_id", actor);
        Account account = jdbc.queryForObject("SELECT * FROM ai_quota_accounts WHERE user_id=? FOR UPDATE", (rs, row) ->
                new Account(instant(rs.getTimestamp("five_start")), instant(rs.getTimestamp("week_start")), rs.getLong("five_used"), rs.getLong("week_used"), rs.getLong("five_reserved"), rs.getLong("week_reserved"), rs.getLong("five_uncertain"), rs.getLong("week_uncertain")), actor);
        boolean fiveExpired = account.fiveStart != null && !now.isBefore(account.fiveStart.plus(FIVE_HOURS));
        boolean weekExpired = account.weekStart != null && !now.isBefore(account.weekStart.plus(SEVEN_DAYS));
        if (fiveExpired) jdbc.update("UPDATE ai_quota_accounts SET five_start=NULL,five_used=0,five_reserved=0,five_uncertain=0 WHERE user_id=?", actor);
        if (weekExpired) jdbc.update("UPDATE ai_quota_accounts SET week_start=NULL,week_used=0,week_reserved=0,week_uncertain=0 WHERE user_id=?", actor);
        return new Account(fiveExpired ? null : account.fiveStart, weekExpired ? null : account.weekStart,
                fiveExpired ? 0 : account.fiveUsed, weekExpired ? 0 : account.weekUsed,
                fiveExpired ? 0 : account.fiveReserved, weekExpired ? 0 : account.weekReserved,
                fiveExpired ? 0 : account.fiveUncertain, weekExpired ? 0 : account.weekUncertain);
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static long seconds(Instant now, Instant reset) { return Math.max(1, (Duration.between(now, reset).toMillis() + 999) / 1000); }
    private record Account(Instant fiveStart, Instant weekStart, long fiveUsed, long weekUsed, long fiveReserved, long weekReserved, long fiveUncertain, long weekUncertain) { }
    private record Operation(String actor, String model, String status, long reserved, long input, long total, Instant fiveStart, Instant weekStart, Instant minute, Instant day) { }
    public record Reservation(String id, long reserved, String model) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Window(long limit, long used, long reserved, long uncertain, long remaining, Instant resetsAt, long resetAfterSeconds) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Snapshot(boolean enabled, Instant serverTime, Window fiveHour, Window weekly) { }
}
