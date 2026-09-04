package com.blueai.system.service;

import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.RagChatProperties;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.system.dto.SystemMetricsResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemMetricsServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final ConversationMessageRepository messageRepository = mock(ConversationMessageRepository.class);
    private final RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
    private final RagChatProperties ragProperties = new RagChatProperties();
    private final HttpClient httpClient = mock(HttpClient.class);

    @AfterEach
    void clearInterruptedFlag() {
        Thread.interrupted();
    }

    @Test
    void reportsNotConfiguredWithoutCallingRuntime() throws Exception {
        SystemMetricsResponse metrics = service().getMetrics();

        assertThat(metrics.aiRuntimeReady()).isFalse();
        assertThat(metrics.aiRuntimeStatus()).isEqualTo("NOT_CONFIGURED");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void reportsReadyForSuccessfulReadinessResponse() throws Exception {
        configureRuntime();
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        SystemMetricsResponse metrics = service().getMetrics();

        assertThat(metrics.aiRuntimeReady()).isTrue();
        assertThat(metrics.aiRuntimeStatus()).isEqualTo("READY");
    }

    @Test
    void reportsNotReadyForNonSuccessfulReadinessResponse() throws Exception {
        configureRuntime();
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        SystemMetricsResponse metrics = service().getMetrics();

        assertThat(metrics.aiRuntimeReady()).isFalse();
        assertThat(metrics.aiRuntimeStatus()).isEqualTo("NOT_READY");
    }

    @Test
    void reportsUnreachableInsteadOfFailingMetrics() throws Exception {
        configureRuntime();
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("connection refused"));

        SystemMetricsResponse metrics = service().getMetrics();

        assertThat(metrics.aiRuntimeReady()).isFalse();
        assertThat(metrics.aiRuntimeStatus()).isEqualTo("UNREACHABLE");
    }

    @Test
    void preservesInterruptedFlagWhenProbeIsInterrupted() throws Exception {
        configureRuntime();
        when(httpClient.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new InterruptedException("interrupted"));

        SystemMetricsResponse metrics = service().getMetrics();

        assertThat(metrics.aiRuntimeReady()).isFalse();
        assertThat(metrics.aiRuntimeStatus()).isEqualTo("UNREACHABLE");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private void configureRuntime() {
        ragProperties.setRetrievalBaseUrl("http://127.0.0.1:8000/");
    }

    private SystemMetricsService service() {
        return new SystemMetricsService(
                userRepository,
                conversationRepository,
                messageRepository,
                documentRepository,
                ragProperties,
                httpClient
        );
    }
}
