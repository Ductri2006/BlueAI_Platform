package com.blueai.rag.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.rag")
public class RagChatProperties {

    private String retrievalBaseUrl = "";
    private String retrievalSearchPath = "/v1/retrieval/search";
    private String llmBaseUrl = "";
    private String llmChatPath = "/v1/chat/completions";
    private String llmApiKey = "";
    private String llmModel = "";
    private int llmMaxAttempts = 3;
    private Duration llmRetryInitialDelay = Duration.ofMillis(250);
    private Duration llmRetryMaxDelay = Duration.ofSeconds(2);
    private boolean allowSharedDemoKnowledge;

    public String getRetrievalBaseUrl() {
        return retrievalBaseUrl;
    }

    public void setRetrievalBaseUrl(String retrievalBaseUrl) {
        this.retrievalBaseUrl = retrievalBaseUrl;
    }

    public String getRetrievalSearchPath() {
        return retrievalSearchPath;
    }

    public void setRetrievalSearchPath(String retrievalSearchPath) {
        this.retrievalSearchPath = retrievalSearchPath;
    }

    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public void setLlmBaseUrl(String llmBaseUrl) {
        this.llmBaseUrl = llmBaseUrl;
    }

    public String getLlmChatPath() {
        return llmChatPath;
    }

    public void setLlmChatPath(String llmChatPath) {
        this.llmChatPath = llmChatPath;
    }

    public String getLlmApiKey() {
        return llmApiKey;
    }

    public void setLlmApiKey(String llmApiKey) {
        this.llmApiKey = llmApiKey;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    public int getLlmMaxAttempts() {
        return llmMaxAttempts;
    }

    public void setLlmMaxAttempts(int llmMaxAttempts) {
        this.llmMaxAttempts = Math.max(1, Math.min(llmMaxAttempts, 5));
    }

    public Duration getLlmRetryInitialDelay() {
        return llmRetryInitialDelay;
    }

    public void setLlmRetryInitialDelay(Duration llmRetryInitialDelay) {
        this.llmRetryInitialDelay = requirePositiveDuration(
                llmRetryInitialDelay,
                Duration.ofMillis(250)
        );
    }

    public Duration getLlmRetryMaxDelay() {
        return llmRetryMaxDelay;
    }

    public void setLlmRetryMaxDelay(Duration llmRetryMaxDelay) {
        this.llmRetryMaxDelay = requirePositiveDuration(
                llmRetryMaxDelay,
                Duration.ofSeconds(2)
        );
    }

    public boolean isAllowSharedDemoKnowledge() {
        return allowSharedDemoKnowledge;
    }

    public void setAllowSharedDemoKnowledge(boolean allowSharedDemoKnowledge) {
        this.allowSharedDemoKnowledge = allowSharedDemoKnowledge;
    }

    public boolean isRetrieverConfigured() {
        return retrievalBaseUrl != null && !retrievalBaseUrl.isBlank();
    }

    public boolean isLlmConfigured() {
        return llmBaseUrl != null && !llmBaseUrl.isBlank()
                && llmApiKey != null && !llmApiKey.isBlank()
                && llmModel != null && !llmModel.isBlank();
    }

    private Duration requirePositiveDuration(Duration candidate, Duration fallback) {
        return candidate == null || candidate.isZero() || candidate.isNegative()
                ? fallback
                : candidate;
    }
}
