package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Short-lived public-widget context. It deliberately stores message hashes and
 * the already-rendered response, not raw visitor IP addresses or an unlimited
 * transcript. MySQL remains the durable source for session state and exact
 * request-id idempotency records.
 */
@Service
public class WidgetConversationContextStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(WidgetConversationContextStore.class);
    private static final Set<String> CLARIFICATION_MARKERS = Set.of(
            "khong hieu", "giai thich", "giai thich lai", "noi ro hon",
            "vi du", "y gi", "tai sao", "nhu the nao", "the la sao"
    );

    private final Map<String, Context> localContexts = new ConcurrentHashMap<>();
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final WidgetProtectionProperties properties;
    private final ObjectMapper objectMapper;
    private final WidgetProtectionMetrics protectionMetrics;
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean();

    public WidgetConversationContextStore(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            WidgetProtectionProperties properties,
            ObjectMapper objectMapper,
            WidgetProtectionMetrics protectionMetrics
    ) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.protectionMetrics = protectionMetrics;
    }

    /**
     * Returns a cached response only for a recently repeated, identical
     * message. Different wording always proceeds to the normal dialog/LLM path
     * so a real clarification request is never discarded as spam.
     */
    public DuplicateResult recordDuplicateAttempt(String sessionId, String messageDigest) {
        Context current = read(sessionId);
        if (current == null
                || !messageDigest.equals(current.messageDigest())
                || current.responseJson() == null
                || !current.expiresAt().isAfter(Instant.now())) {
            return DuplicateResult.notDuplicate();
        }

        Instant now = Instant.now();
        Context updated = new Context(
                current.messageDigest(),
                current.responseJson(),
                current.duplicateCount() + 1,
                current.clarificationCount(),
                now,
                now.plus(properties.getDuplicateWindow())
        );
        write(sessionId, updated, properties.getDuplicateWindow());
        return new DuplicateResult(updated.responseJson(), updated.duplicateCount());
    }

    public void recordResponse(String sessionId, String messageDigest, String responseJson, String message) {
        Context previous = read(sessionId);
        int clarificationCount = isClarification(message)
                ? (previous == null ? 0 : previous.clarificationCount()) + 1
                : 0;
        Instant now = Instant.now();
        Context updated = new Context(
                messageDigest,
                responseJson,
                0,
                clarificationCount,
                now,
                now.plus(properties.getDuplicateWindow())
        );
        write(sessionId, updated, properties.getDuplicateWindow());
    }

    public boolean isClarification(String message) {
        String normalized = Normalizer.normalize(message == null ? "" : message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return CLARIFICATION_MARKERS.stream().anyMatch(normalized::contains);
    }

    int localContextCount() {
        return localContexts.size();
    }

    private Context read(String sessionId) {
        if (properties.isRedisEnabled() && redisTemplateProvider != null) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    String value = redis.opsForValue().get(redisKey(sessionId));
                    if (value == null || value.isBlank()) return null;
                    redisFailureLogged.set(false);
                    return objectMapper.readValue(value, Context.class);
                } catch (DataAccessException | JsonProcessingException exception) {
                    logRedisFallback(exception);
                }
            }
        }
        Context context = localContexts.get(sessionId);
        if (context != null && !context.expiresAt().isAfter(Instant.now())) {
            localContexts.remove(sessionId, context);
            return null;
        }
        return context;
    }

    private void write(String sessionId, Context context, java.time.Duration ttl) {
        if (properties.isRedisEnabled() && redisTemplateProvider != null) {
            StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
            if (redis != null) {
                try {
                    redis.opsForValue().set(redisKey(sessionId), objectMapper.writeValueAsString(context), ttl);
                    redisFailureLogged.set(false);
                    return;
                } catch (DataAccessException | JsonProcessingException exception) {
                    logRedisFallback(exception);
                }
            }
        }
        localContexts.put(sessionId, context);
        if (localContexts.size() > 10_000) {
            Instant now = Instant.now();
            localContexts.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
    }

    private String redisKey(String sessionId) {
        return properties.getRedisKeyPrefix() + "context:" + sessionId;
    }

    private void logRedisFallback(Exception exception) {
        if (redisFailureLogged.compareAndSet(false, true)) {
            protectionMetrics.record(WidgetProtectionMetrics.Event.REDIS_FALLBACK, WidgetProtectionMetrics.Scope.CONTEXT);
            LOGGER.warn("Widget Redis context is unavailable; using per-instance fallback until it recovers.", exception);
        }
    }

    public record DuplicateResult(String responseJson, int repeatCount) {
        static DuplicateResult notDuplicate() {
            return new DuplicateResult(null, 0);
        }

        public boolean isDuplicate() {
            return responseJson != null;
        }
    }

    private record Context(
            String messageDigest,
            String responseJson,
            int duplicateCount,
            int clarificationCount,
            Instant lastMessageAt,
            Instant expiresAt
    ) {
    }
}
