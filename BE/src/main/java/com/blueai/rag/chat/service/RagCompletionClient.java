package com.blueai.rag.chat.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.rag.chat.RagChatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.io.UncheckedIOException;
import java.util.List;

/** Shared LLM transport for answers and background conversation summarization. */
@Service
public class RagCompletionClient {

    private static final Logger LOG = LoggerFactory.getLogger(RagCompletionClient.class);

    private final RagChatProperties properties;
    private final RagRestClientFactory restClientFactory;

    public RagCompletionClient(RagChatProperties properties, RagRestClientFactory restClientFactory) {
        this.properties = properties;
        this.restClientFactory = restClientFactory;
    }

    public String complete(List<LlmChatMessage> messages) {
        if (!properties.isLlmConfigured()) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "LLM_NOT_CONFIGURED",
                    "LLM is not configured. Set APP_LLM_BASE_URL, APP_LLM_API_KEY, and APP_LLM_MODEL first."
            );
        }

        int maxAttempts = properties.getLlmMaxAttempts();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                RestClient client = restClientFactory.create()
                        .baseUrl(properties.getLlmBaseUrl())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getLlmApiKey())
                        .build();
                LlmResponse response = client
                    .post()
                    .uri(properties.getLlmChatPath())
                    .body(new LlmRequest(properties.getLlmModel(), messages, 0.2))
                    .retrieve()
                    .body(LlmResponse.class);

                if (response == null || response.choices() == null || response.choices().isEmpty()
                        || response.choices().getFirst().message() == null
                        || response.choices().getFirst().message().content() == null
                        || response.choices().getFirst().message().content().isBlank()) {
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "LLM_INVALID_RESPONSE",
                            "LLM returned an empty response."
                    );
                }
                return response.choices().getFirst().message().content().trim();
            } catch (RestClientException | UncheckedIOException exception) {
                if (attempt >= maxAttempts || !isTransient(exception)) {
                    throw requestFailed();
                }

                long delayMillis = retryDelayMillis(attempt);
                LOG.warn(
                        "Transient LLM request failure ({}) on attempt {}/{}; retrying in {} ms.",
                        failureKind(exception), attempt, maxAttempts, delayMillis
                );
                sleepBeforeRetry(delayMillis);
            }
        }

        throw requestFailed();
    }

    private boolean isTransient(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == HttpStatus.REQUEST_TIMEOUT.value()
                    || status == HttpStatus.TOO_MANY_REQUESTS.value()
                    || responseException.getStatusCode().is5xxServerError();
        }
        return exception instanceof ResourceAccessException || exception instanceof UncheckedIOException;
    }

    private long retryDelayMillis(int failedAttempt) {
        long initial = Math.max(1, properties.getLlmRetryInitialDelay().toMillis());
        long maximum = Math.max(initial, properties.getLlmRetryMaxDelay().toMillis());
        long multiplier = 1L << Math.min(Math.max(0, failedAttempt - 1), 4);
        return Math.min(maximum, initial * multiplier);
    }

    private void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw requestFailed();
        }
    }

    private String failureKind(RuntimeException exception) {
        if (exception instanceof RestClientResponseException responseException) {
            return "HTTP " + responseException.getStatusCode().value();
        }
        return exception.getClass().getSimpleName();
    }

    private ApiException requestFailed() {
        return new ApiException(
                HttpStatus.BAD_GATEWAY,
                "LLM_REQUEST_FAILED",
                "LLM could not be reached after retrying transient failures."
        );
    }

    private record LlmRequest(String model, List<LlmChatMessage> messages, double temperature) {
    }

    private record LlmResponse(List<LlmChoice> choices) {
    }

    private record LlmChoice(LlmChatMessage message) {
    }
}
