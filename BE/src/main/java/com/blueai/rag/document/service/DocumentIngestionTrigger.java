package com.blueai.rag.document.service;

import com.blueai.rag.chat.RagChatProperties;
import com.blueai.rag.chat.service.RagRestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * Wakes the Python document worker right after an upload so chunking starts immediately
 * instead of waiting for the next poll.
 *
 * <p>This is only a nudge: the worker still claims the row through
 * {@code /api/v1/internal/documents/claim}, so the pull model and its trust boundary are
 * unchanged. A failed nudge therefore costs latency, never correctness — the periodic poll
 * picks the document up anyway.
 */
@Component
public class DocumentIngestionTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentIngestionTrigger.class);
    private static final String TRIGGER_PATH = "/v1/internal/ingest/run";
    private static final String PURGE_PATH = "/v1/internal/documents/";
    private static final String WORKER_API_KEY_HEADER = "X-Worker-Api-Key";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RagChatProperties properties;
    private final RagRestClientFactory restClientFactory;
    private final String workerApiKey;

    public DocumentIngestionTrigger(
            RagChatProperties properties,
            RagRestClientFactory restClientFactory,
            @Value("${app.worker.api-key}") String workerApiKey
    ) {
        this.properties = properties;
        this.restClientFactory = restClientFactory;
        this.workerApiKey = workerApiKey;
    }

    /**
     * @return true when the RAG service accepted the nudge; false when no retriever is
     *         configured or the call failed. Never throws: an upload must not fail because
     *         the worker is unreachable.
     */
    public boolean requestIngestion() {
        if (!properties.isRetrieverConfigured()) {
            return false;
        }
        try {
            restClientFactory.create()
                    .baseUrl(properties.getRetrievalBaseUrl())
                    .requestFactory(timeoutRequestFactory())
                    .defaultHeader(WORKER_API_KEY_HEADER, workerApiKey)
                    .build()
                    .post()
                    .uri(TRIGGER_PATH)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException exception) {
            LOG.warn("Could not wake the RAG document worker at {}{}: {}. The document stays "
                            + "PENDING_CHUNKING until the worker polls; check that the worker's "
                            + "JAVA_API_BASE_URL and JAVA_WORKER_API_KEY match this service.",
                    properties.getRetrievalBaseUrl(), TRIGGER_PATH, exception.getMessage());
            return false;
        }
    }

    /**
     * Removes the document's chunks from the Python FAISS/docstore. A false
     * result is deliberately non-authoritative: the Java tombstone remains and
     * the retention purge can retry this operation safely.
     */
    public boolean requestDocumentPurge(String documentId) {
        if (!properties.isRetrieverConfigured()) {
            return false;
        }
        try {
            restClientFactory.create()
                    .baseUrl(properties.getRetrievalBaseUrl())
                    .requestFactory(timeoutRequestFactory())
                    .defaultHeader(WORKER_API_KEY_HEADER, workerApiKey)
                    .build()
                    .post()
                    .uri(PURGE_PATH + documentId + "/purge")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException exception) {
            LOG.warn("Could not purge document {} from the RAG vector store: {}. The tombstone "
                            + "remains and retention purge must retry it.",
                    documentId, exception.getMessage());
            return false;
        }
    }

    private SimpleClientHttpRequestFactory timeoutRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return requestFactory;
    }
}
