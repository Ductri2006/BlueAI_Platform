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
    private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
            """
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end
                    return count
                    """,
            Long.class
    );

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
        long count = increment(key, window);
        if (count > maxRequests) {
            protectionMetrics.record(WidgetProtectionMetrics.Event.RATE_LIMITED, scopeFor(key));
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, errorCode, message);
        }
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

    private long increment(String key, Duration window) {
        if (properties.isRedisEnabled()) {
            StringRedisTemplate redis = redisTemplateProvider == null ? null : redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    Long result = redis.execute(
                            INCREMENT_WITH_TTL,
                            List.of(redisKey("rate:" + key)),
                            Long.toString(window.toMillis())
                    );
                    if (result != null) {
                        redisFailureLogged.set(false);
                        return result;
                    }
                } catch (DataAccessException exception) {
                    logRedisFallback(exception);
                }
            }
            throw protectionUnavailable();
        }
        return incrementLocal(key, window);
    }

    private synchronized long incrementLocal(String key, Duration window) {
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

        return updated.count();
    }

    private ApiException protectionUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WIDGET_PROTECTION_UNAVAILABLE",
                "Widget protection is temporarily unavailable. Please try again later.");
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
