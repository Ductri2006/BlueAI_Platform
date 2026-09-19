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
 * TTL atomically. A short-lived in-process fallback keeps a single local
 * developer instance safe if Redis is intentionally disabled or unavailable;
 * it must not be mistaken for distributed protection.</p>
 */
@Service
public class WidgetRateLimiter {

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
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

    public WidgetRateLimiter(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            WidgetProtectionProperties properties
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
    }

    public void check(String key, int maxRequests, Duration window, String message) {
        if (maxRequests < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Widget rate-limit configuration must be positive.");
        }
        long count = increment(key, window);
        if (count > maxRequests) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "WIDGET_RATE_LIMITED", message);
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
        if (properties.isRedisEnabled() && redisTemplateProvider != null) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
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
        }
        return incrementLocal(key, window);
    }

    private long incrementLocal(String key, Duration window) {
        Instant now = Instant.now();
        Window updated = localWindows.compute(key, (ignored, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                return new Window(now.plus(window), 1);
            }
            return new Window(current.expiresAt(), current.count() + 1);
        });

        // Bound memory in the local fallback. Redis keys expire automatically.
        if (localWindows.size() > 10_000) {
            localWindows.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
        return updated.count();
    }

    private String redisKey(String suffix) {
        return properties.getRedisKeyPrefix() + suffix;
    }

    private void logRedisFallback(DataAccessException exception) {
        if (redisFailureLogged.compareAndSet(false, true)) {
            LOGGER.warn("Widget Redis protection is unavailable; using per-instance fallback until it recovers.", exception);
        }
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
