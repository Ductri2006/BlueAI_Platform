package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serializes requests for one widget session. This prevents concurrent calls
 * from evaluating the same conversation state twice before its idempotency row
 * is committed. Locks are short-lived and carry a random owner token, so a
 * completed request cannot delete a later owner's lock.
 */
@Service
public class WidgetSessionRequestCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(WidgetSessionRequestCoordinator.class);
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            """
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    end
                    return 0
                    """,
            Long.class
    );

    private final Map<String, String> localLocks = new ConcurrentHashMap<>();
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final WidgetProtectionProperties properties;
    private final WidgetProtectionMetrics protectionMetrics;
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

    public WidgetSessionRequestCoordinator(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            WidgetProtectionProperties properties,
            WidgetProtectionMetrics protectionMetrics
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
        this.protectionMetrics = protectionMetrics;
    }

    public LockHandle tryAcquire(String sessionId) {
        String ownerToken = UUID.randomUUID().toString();
        if (properties.isRedisEnabled() && redisTemplateProvider != null) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    Boolean acquired = redis.opsForValue().setIfAbsent(
                            redisKey(sessionId), ownerToken, properties.getRequestLockTtl()
                    );
                    redisFailureLogged.set(false);
                    return new LockHandle(this, sessionId, ownerToken, Boolean.TRUE.equals(acquired), true);
                } catch (DataAccessException exception) {
                    logRedisFallback(exception);
                }
            }
        }
        boolean acquired = localLocks.putIfAbsent(sessionId, ownerToken) == null;
        return new LockHandle(this, sessionId, ownerToken, acquired, false);
    }

    private void release(LockHandle handle) {
        if (!handle.acquired()) return;
        if (handle.redisBacked() && properties.isRedisEnabled() && redisTemplateProvider != null) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    redis.execute(RELEASE_IF_OWNER, List.of(redisKey(handle.sessionId())), handle.ownerToken());
                    redisFailureLogged.set(false);
                    return;
                } catch (DataAccessException exception) {
                    logRedisFallback(exception);
                }
            }
        }
        localLocks.remove(handle.sessionId(), handle.ownerToken());
    }

    private String redisKey(String sessionId) {
        return properties.getRedisKeyPrefix() + "lock:session:" + sessionId;
    }

    private void logRedisFallback(DataAccessException exception) {
        if (redisFailureLogged.compareAndSet(false, true)) {
            protectionMetrics.record(WidgetProtectionMetrics.Event.REDIS_FALLBACK, WidgetProtectionMetrics.Scope.REQUEST_LOCK);
            LOGGER.warn("Widget Redis request lock is unavailable; using per-instance fallback until it recovers.", exception);
        }
    }

    public record LockHandle(
            WidgetSessionRequestCoordinator coordinator,
            String sessionId,
            String ownerToken,
            boolean acquired,
            boolean redisBacked
    ) implements AutoCloseable {
        @Override
        public void close() {
            coordinator.release(this);
        }
    }
}
