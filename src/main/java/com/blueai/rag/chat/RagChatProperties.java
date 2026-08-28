package com.blueai.rag.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public class RagChatProperties {

    private String retrievalBaseUrl = "";
    private String retrievalSearchPath = "/v1/retrieval/search";
    private String llmBaseUrl = "";
    private String llmChatPath = "/v1/chat/completions";
    private String llmApiKey = "";
    private String llmModel = "";

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

    public boolean isRetrieverConfigured() {
        return retrievalBaseUrl != null && !retrievalBaseUrl.isBlank();
    }

    public boolean isLlmConfigured() {
        return llmBaseUrl != null && !llmBaseUrl.isBlank()
                && llmApiKey != null && !llmApiKey.isBlank()
                && llmModel != null && !llmModel.isBlank();
    }
}
