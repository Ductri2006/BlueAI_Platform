package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.studio.config.WidgetProtectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rate limiter for public-widget endpoints.
 *
 * <p>When configured, the counter lives in Redis and is consequently shared
 * by all API instances. The Lua script increments the counter and applies its
 * TTL atomically. Redis-enabled protection fails closed if shared counters are
 * unavailable. The bounded in-process counter is only for local development
 * with Redis intentionally disabled, never distributed protection.</p>
 */
@Service
public class WidgetRateLimiter {

    private static final int MAX_LOCAL_WINDOWS = 10_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(WidgetRateLimiter.class);
    private static final DefaultRedisScript<List> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
            """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end
                    return {count, redis.call('PTTL', KEYS[1])}
                    """,
            List.class
    );
    private static final DefaultRedisScript<List> READ_WITH_TTL = new DefaultRedisScript<>(
            "local count = tonumber(redis.call('GET', KEYS[1]) or '0'); "
                    + "local ttl = redis.call('PTTL', KEYS[1]); "
                    + "if ttl <= 0 then return {0, 0} end; return {count, ttl}", List.class);

    private final Map<String, Window> localWindows = new ConcurrentHashMap<>();
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final WidgetProtectionProperties properties;
    private final WidgetProtectionMetrics protectionMetrics;
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

    public WidgetRateLimiter(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            WidgetProtectionProperties properties,
            WidgetProtectionMetrics protectionMetrics
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
        this.protectionMetrics = protectionMetrics;
    }

    public void check(String key, int maxRequests, Duration window, String message) {
        check(key, maxRequests, window, message, "WIDGET_RATE_LIMITED");
    }

    public void check(String key, int maxRequests, Duration window, String message, String errorCode) {
        if (maxRequests < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Widget rate-limit configuration must be positive.");
        }
        Window current = increment(key, window);
        if (current.count() > maxRequests) {
            protectionMetrics.record(WidgetProtectionMetrics.Event.RATE_LIMITED, scopeFor(key));
            long retryAfter = Math.max(1, (Duration.between(Instant.now(), current.expiresAt()).toMillis() + 999) / 1000);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, errorCode, message, retryAfter);
        }
    }

    public record QuotaStatus(int limit, long remaining, int windowSeconds, Instant resetsAt, long resetAfterSeconds) { }

    /** Reading quota never creates a counter or extends its expiration. */
    public QuotaStatus snapshot(String key, int limit, Duration window) {
        Window current;
        if (properties.isRedisEnabled()) {
            current = redisWindow(READ_WITH_TTL, key, window);
        } else {
            current = localWindows.get(key);
            if (current != null && !current.expiresAt().isAfter(Instant.now())) {
                localWindows.remove(key, current);
                current = null;
            }
        }
        long count = current == null ? 0 : current.count();
        long resetAfter = current == null || count == 0 ? 0
                : Math.max(0, (Duration.between(Instant.now(), current.expiresAt()).toMillis() + 999) / 1000);
        return new QuotaStatus(limit, Math.max(0, limit - count), (int) window.toSeconds(),
                current == null || count == 0 ? null : current.expiresAt(), resetAfter);
    }

    /**
     * Creates a stable but pseudonymous key for an IP address. Redis never
     * needs the raw address for this purpose.
     */
    public String clientKey(String clientAddress) {
        String address = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.trim();
        String salt = properties.getClientKeySalt() == null ? "" : properties.getClientKeySalt();
        return sha256(salt + '\u0000' + address);
    }

    int trackedWindowCount() {
        return localWindows.size();
    }

    private Window increment(String key, Duration window) {
        if (properties.isRedisEnabled()) {
            return redisWindow(INCREMENT_WITH_TTL, key, window);
        }
        return incrementLocal(key, window);
    }

    private Window redisWindow(DefaultRedisScript<List> script, String key, Duration window) {
        StringRedisTemplate redis = redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
        if (redis != null) {
            try {
                List<?> result = redis.execute(script, List.of(redisKey("rate:" + key)), Long.toString(window.toMillis()));
                if (result != null && result.size() == 2 && result.get(0) instanceof Number count
                        && result.get(1) instanceof Number ttl && ttl.longValue() >= 0) {
                    redisFailureLogged.set(false);
                    return new Window(Instant.now().plusMillis(ttl.longValue()), count.longValue());
                }
            } catch (DataAccessException exception) {
                logRedisFallback(exception);
            }
        }
        throw protectionUnavailable();
    }

    private synchronized Window incrementLocal(String key, Duration window) {
        Instant now = Instant.now();
        if (!localWindows.containsKey(key) && localWindows.size() >= MAX_LOCAL_WINDOWS) {
            localWindows.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
            if (localWindows.size() >= MAX_LOCAL_WINDOWS) {
                throw protectionUnavailable();
            }
        }
        Window updated = localWindows.compute(key, (ignored, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                return new Window(now.plus(window), 1);
            }
            return new Window(current.expiresAt(), current.count() + 1);
        });

        return updated;
    }

    private ApiException protectionUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WIDGET_PROTECTION_UNAVAILABLE",
                "Request protection is temporarily unavailable. Please try again later.");
    }

    private String redisKey(String suffix) {
        return properties.getRedisKeyPrefix() + suffix;
    }

    private void logRedisFallback(DataAccessException exception) {
        if (redisFailureLogged.compareAndSet(false, true)) {
            protectionMetrics.record(WidgetProtectionMetrics.Event.REDIS_FALLBACK, WidgetProtectionMetrics.Scope.RATE_LIMIT);
            LOGGER.warn("Widget Redis protection is unavailable; widget requests are blocked until shared counters recover.");
        }
    }

    private WidgetProtectionMetrics.Scope scopeFor(String key) {
        if (key.startsWith("session:")) {
            return WidgetProtectionMetrics.Scope.SESSION_CREATION;
        }
        if (key.startsWith("message-client:")) {
            return WidgetProtectionMetrics.Scope.MESSAGE_CLIENT;
        }
        if (key.startsWith("message-session:")) {
            return WidgetProtectionMetrics.Scope.MESSAGE_SESSION;
        }
        return WidgetProtectionMetrics.Scope.RATE_LIMIT;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record Window(Instant expiresAt, long count) {
    }
}
