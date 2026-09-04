package com.blueai.system.service;

import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.RagChatProperties;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.document.model.DocumentStatus;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.system.dto.SystemMetricsResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

@Service
public class SystemMetricsService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(750);

    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final RagDocumentRepository documentRepository;
    private final RagChatProperties ragProperties;
    private final HttpClient httpClient;

    @Autowired
    public SystemMetricsService(
            UserRepository userRepository,
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository,
            RagDocumentRepository documentRepository,
            RagChatProperties ragProperties
    ) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.documentRepository = documentRepository;
        this.ragProperties = ragProperties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    SystemMetricsService(
            UserRepository userRepository,
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository,
            RagDocumentRepository documentRepository,
            RagChatProperties ragProperties,
            HttpClient httpClient
    ) {
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.documentRepository = documentRepository;
        this.ragProperties = ragProperties;
        this.httpClient = httpClient;
    }

    @Transactional(readOnly = true)
    public SystemMetricsResponse getMetrics() {
        AiRuntimeReadiness readiness = probeAiRuntime();
        return new SystemMetricsResponse(
                userRepository.count(),
                conversationRepository.count(),
                messageRepository.count(),
                documentRepository.count(),
                documentRepository.countByStatus(DocumentStatus.READY),
                documentRepository.countByStatusIn(java.util.List.of(DocumentStatus.PENDING_CHUNKING, DocumentStatus.PROCESSING)),
                documentRepository.countByStatus(DocumentStatus.FAILED),
                ragProperties.isRetrieverConfigured(),
                ragProperties.isLlmConfigured(),
                ragProperties.getLlmModel(),
                readiness.ready(),
                readiness.status(),
                Instant.now()
        );
    }

    private AiRuntimeReadiness probeAiRuntime() {
        if (!ragProperties.isRetrieverConfigured()) {
            return new AiRuntimeReadiness(false, "NOT_CONFIGURED");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(readyUri(ragProperties.getRetrievalBaseUrl()))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            boolean ready = response.statusCode() >= 200 && response.statusCode() < 300;
            return new AiRuntimeReadiness(ready, ready ? "READY" : "NOT_READY");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new AiRuntimeReadiness(false, "UNREACHABLE");
        } catch (IOException | RuntimeException exception) {
            return new AiRuntimeReadiness(false, "UNREACHABLE");
        }
    }

    private URI readyUri(String baseUrl) {
        String normalizedBaseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        return URI.create(normalizedBaseUrl + "/ready");
    }

    private record AiRuntimeReadiness(boolean ready, String status) {
    }
}
