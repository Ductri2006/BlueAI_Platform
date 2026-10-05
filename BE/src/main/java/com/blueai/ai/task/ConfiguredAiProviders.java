package com.blueai.ai.task;

import com.blueai.rag.chat.RagChatProperties;
import com.blueai.system.service.GeminiUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Configured provider adapters with bounded bodies and abortable deadline enforcement. */
@Component
@EnableConfigurationProperties(AiTaskProperties.class)
public class ConfiguredAiProviders implements AiCompletionProvider, AiRetrievalProvider {
    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredAiProviders.class);
    private static final int MAX_PROVIDER_RESPONSE = 32 * 1024;
    private static final Semaphore UPSTREAM_SLOTS = new Semaphore(32);
    private static final ScheduledThreadPoolExecutor DEADLINES = newDeadlineScheduler();

    private final RagChatProperties config;
    private final ObjectMapper mapper;
    private final CloseableHttpClient httpClient;
    private final GeminiUsageService usageService;

    public ConfiguredAiProviders(RagChatProperties config, ObjectMapper mapper, AiTaskProperties ignored) {
        this(config, mapper, ignored, null);
    }

    @Autowired
    public ConfiguredAiProviders(RagChatProperties config, ObjectMapper mapper, AiTaskProperties ignored,
                                 GeminiUsageService usageService) {
        this.config = config;
        this.mapper = mapper;
        this.usageService = usageService;
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(32)
                .setMaxConnPerRoute(16)
                .build();
        this.httpClient = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries().build();
    }

    @PreDestroy
    void closeHttpClient() throws java.io.IOException {
        httpClient.close();
        DEADLINES.shutdownNow();
    }

    @Override
    public String complete(String system, String input, List<Map<String, String>> history, Instant deadline) {
        if (!config.isLlmConfigured()) {
            throw new AiTaskException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_NOT_CONFIGURED", "Configured AI provider is unavailable.");
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.addAll(history);
        messages.add(Map.of("role", "user", "content", input));
        JsonNode response = post(config.getLlmBaseUrl(), config.getLlmChatPath(),
                Map.of("model", config.getLlmModel(), "messages", messages, "temperature", 0.0),
                config.getLlmApiKey(), deadline, false);
        if (!response.isObject() || !response.path("choices").isArray() || response.path("choices").isEmpty()) {
            throw invalid();
        }
        JsonNode content = response.path("choices").get(0).path("message").path("content");
        if (!content.isTextual()) throw invalid();
        JsonNode usage = response.path("usage");
        if (usageService != null && usage.path("prompt_tokens").isIntegralNumber()
                && usage.path("completion_tokens").isIntegralNumber()
                && usage.path("total_tokens").isIntegralNumber()) {
            try {
                usageService.record(response.path("model").asText(config.getLlmModel()),
                        usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong(),
                        usage.path("total_tokens").asLong());
            } catch (DataAccessException | TransactionException exception) {
                LOG.warn("Could not persist Gemini token usage for a successful AI task completion.", exception);
            }
        }
        return content.asText();
    }

    @Override
    public JsonNode search(String owner, String query, List<String> allowlist, Instant deadline) {
        if (!config.isRetrieverConfigured()) {
            throw new AiTaskException(HttpStatus.SERVICE_UNAVAILABLE, "AI_RETRIEVAL_NOT_CONFIGURED", "RAG retrieval is unavailable.");
        }
        JsonNode response = post(config.getRetrievalBaseUrl(), config.getRetrievalSearchPath(),
                Map.of("userId", owner, "query", query, "limit", 20, "allowedDocumentIds", allowlist), null, deadline, true);
        if (!response.isObject() || !response.path("matches").isArray() || response.path("matches").size() > 20) throw invalid();
        Set<String> chunkIds = new HashSet<>();
        for (JsonNode match : response.path("matches")) {
            if (!match.isObject() || !nonBlank(match.path("documentId"), 64)
                    || !nonBlank(match.path("chunkId"), 128) || !nonBlank(match.path("content"), 6000)
                    || !nonBlank(match.path("filename"), 255) || !chunkIds.add(match.path("chunkId").asText())) {
                throw invalid();
            }
        }
        return response.path("matches");
    }

    private JsonNode post(String base, String path, Object payload, String apiKey, Instant deadline, boolean retrieval) {
        if (!UPSTREAM_SLOTS.tryAcquire()) {
            throw new AiTaskException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_CAPACITY", "AI provider capacity is temporarily full.");
        }
        HttpPost request = null;
        ScheduledFuture<?> cancellation = null;
        AtomicBoolean expired = new AtomicBoolean(false);
        try {
            long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
            if (remainingMillis <= 0) throw deadlineExceeded();
            request = new HttpPost(URI.create(base + path));
            request.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
            if (apiKey != null) request.setHeader("Authorization", "Bearer " + apiKey);
            request.setEntity(new ByteArrayEntity(mapper.writeValueAsBytes(payload), ContentType.APPLICATION_JSON));
            request.setConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(remainingMillis))
                    .setConnectTimeout(Timeout.ofMilliseconds(Math.min(2000, remainingMillis)))
                    .setResponseTimeout(Timeout.ofMilliseconds(remainingMillis))
                    .build());

            HttpPost activeRequest = request;
            cancellation = DEADLINES.schedule(() -> {
                expired.set(true);
                activeRequest.cancel();
            }, remainingMillis, TimeUnit.MILLISECONDS);

            return httpClient.execute(request, response -> {
                int status = response.getCode();
                if (status < 200 || status >= 300) {
                    if (status == 429) throw new AiTaskException(HttpStatus.TOO_MANY_REQUESTS, "AI_PROVIDER_RATE_LIMITED", "Configured AI upstream rate limit was reached.");
                    throw new AiTaskException(HttpStatus.BAD_GATEWAY,
                            retrieval ? "AI_RETRIEVAL_FAILED" : "AI_PROVIDER_FAILED",
                            "Configured AI upstream returned an unsuccessful status.");
                }
                HttpEntity entity = response.getEntity();
                if (entity == null) throw invalid();
                try (InputStream body = entity.getContent()) {
                    byte[] bytes = body.readNBytes(MAX_PROVIDER_RESPONSE + 1);
                    if (bytes.length > MAX_PROVIDER_RESPONSE) throw invalid();
                    if (expired.get() || Instant.now().isAfter(deadline)) throw deadlineExceeded();
                    return mapper.readTree(bytes);
                }
            });
        } catch (AiTaskException exception) {
            throw exception;
        } catch (Exception exception) {
            if (expired.get() || Instant.now().isAfter(deadline)) throw deadlineExceeded();
            throw new AiTaskException(HttpStatus.BAD_GATEWAY,
                    retrieval ? "AI_RETRIEVAL_FAILED" : "AI_PROVIDER_FAILED",
                    "Configured AI upstream request failed.");
        } finally {
            if (cancellation != null) cancellation.cancel(false);
            if (request != null) request.cancel();
            UPSTREAM_SLOTS.release();
        }
    }

    private boolean nonBlank(JsonNode value, int max) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= max;
    }

    private static ScheduledThreadPoolExecutor newDeadlineScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ai-task-deadline");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private AiTaskException deadlineExceeded() {
        return new AiTaskException(HttpStatus.GATEWAY_TIMEOUT, "AI_TASK_DEADLINE_EXCEEDED", "AI task deadline was exceeded.");
    }

    private AiTaskException invalid() {
        return new AiTaskException(HttpStatus.BAD_GATEWAY, "AI_INVALID_OUTPUT", "AI provider returned output that failed validation.");
    }
}
