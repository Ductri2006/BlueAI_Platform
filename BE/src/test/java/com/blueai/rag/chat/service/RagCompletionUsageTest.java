package com.blueai.rag.chat.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.rag.chat.RagChatProperties;
import com.blueai.system.service.GeminiUsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RagCompletionUsageTest {
    private final GeminiUsageService usageService = mock(GeminiUsageService.class);
    private final RagRestClientFactory factory = mock(RagRestClientFactory.class);
    private final RagChatProperties properties = new RagChatProperties();
    private MockRestServiceServer server;
    private RagCompletionClient client;

    @BeforeEach
    void setUp() {
        properties.setLlmBaseUrl("http://gemini.test");
        properties.setLlmApiKey("test-key");
        properties.setLlmModel("configured-model");
        properties.setLlmRetryInitialDelay(Duration.ofMillis(1));
        properties.setLlmRetryMaxDelay(Duration.ofMillis(1));
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        when(factory.create()).thenAnswer(invocation -> builder.clone());
        client = new RagCompletionClient(properties, factory, usageService, "test-worker-key",
                URI.create("http://gemini.test"));
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void sendsTheWorkerKeyOnlyToTheConfiguredOrigin() {
        properties.setLlmBaseUrl("http://gemini.test:8081");
        server.expect(requestTo("http://gemini.test:8081/v1/chat/completions"))
                .andExpect(headerDoesNotExist("X-Worker-Api-Key"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(reply(null), MediaType.APPLICATION_JSON));
        assertThat(ask()).isEqualTo("Answer");
    }

    @Test
    void recordsActualModelAndProviderTotalsIncludingThinkingTokens() {
        expectReply(reply("{\"prompt_tokens\":120,\"completion_tokens\":24,\"total_tokens\":160}"));
        assertThat(ask()).isEqualTo("Answer");
        verify(usageService).record("gemini-actual", 120, 24, 160);
        verifyNoMoreInteractions(usageService);
    }

    @Test
    void usesConfiguredModelIfProviderOmitsModel() {
        expectReply(reply("{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}")
                .replace("\"model\":\"gemini-actual\",", ""));
        ask();
        verify(usageService).record("configured-model", 1, 2, 3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{\"prompt_tokens\":10}",
            "{\"prompt_tokens\":10,\"completion_tokens\":2}",
            "{\"prompt_tokens\":10,\"total_tokens\":12}",
            "{\"completion_tokens\":2,\"total_tokens\":12}",
            "{\"prompt_tokens\":null,\"completion_tokens\":2,\"total_tokens\":12}"
    })
    void missingOrPartialUsageDoesNotInventTokenCounts(String usage) {
        expectReply(reply(usage));
        assertThat(ask()).isEqualTo("Answer");
        verifyNoInteractions(usageService);
    }

    @Test
    void responseWithoutUsageRemainsCompatible() {
        expectReply(reply(null));
        assertThat(ask()).isEqualTo("Answer");
        verifyNoInteractions(usageService);
    }

    @Test
    void zeroTokensAreStillAValidReportedCall() {
        expectReply(reply("{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"));
        ask();
        verify(usageService).record("gemini-actual", 0, 0, 0);
    }

    @Test
    void tokenCountsUseLongsWithoutIntegerOverflow() {
        expectReply(reply("{\"prompt_tokens\":5000000000,\"completion_tokens\":2,\"total_tokens\":5000000002}"));
        ask();
        verify(usageService).record("gemini-actual", 5_000_000_000L, 2, 5_000_000_002L);
    }

    @Test
    void successfulRetryIsRecordedOnlyOnce() {
        server.expect(requestTo("http://gemini.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectReply(reply("{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}"));
        assertThat(ask()).isEqualTo("Answer");
        verify(usageService).record("gemini-actual", 10, 2, 12);
        verifyNoMoreInteractions(usageService);
    }

    @Test
    void exhaustedRetriesDoNotRecordAnUnreportedCall() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo("http://gemini.test/v1/chat/completions"))
                    .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        }
        assertThatThrownBy(this::ask).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("LLM_REQUEST_FAILED"));
        verifyNoInteractions(usageService);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    void permanentHttpFailureDoesNotRetryOrRecordUsage(int status) {
        server.expect(requestTo("http://gemini.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        assertThatThrownBy(this::ask).isInstanceOf(ApiException.class);
        verifyNoInteractions(usageService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "{}", "{\"choices\":[]}",
            "{\"choices\":[{}]}", "{\"choices\":[{\"message\":{\"content\":\"   \"}}]}"
    })
    void invalidCompletionDoesNotCountAsASuccessfulAnswer(String response) {
        expectReply(response);
        assertThatThrownBy(this::ask).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("LLM_INVALID_RESPONSE"));
        verifyNoInteractions(usageService);
    }

    @Test
    void storageFailureDoesNotRepeatThePaidGeminiRequest() {
        expectReply(reply("{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}"));
        doThrow(new DataAccessResourceFailureException("Injected database outage"))
                .when(usageService).record("gemini-actual", 10, 2, 12);
        assertThat(ask()).isEqualTo("Answer");
        verify(usageService, times(1)).record("gemini-actual", 10, 2, 12);
        verify(factory, times(1)).create();
    }

    @Test
    void eachNewPromptGetsItsOwnUsageRecord() {
        expectReply(reply("{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}"));
        expectReply(reply("{\"prompt_tokens\":50,\"completion_tokens\":7,\"total_tokens\":57}"));
        ask();
        ask();
        verify(usageService).record("gemini-actual", 10, 2, 12);
        verify(usageService).record("gemini-actual", 50, 7, 57);
        verifyNoMoreInteractions(usageService);
    }

    @Test
    void unconfiguredProviderDoesNotSendOrRecordAnything() {
        properties.setLlmBaseUrl("");
        assertThatThrownBy(this::ask).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("LLM_NOT_CONFIGURED"));
        verifyNoInteractions(factory, usageService);
    }

    private String ask() {
        return client.complete(List.of(new LlmChatMessage("system", "System instructions"),
                new LlmChatMessage("user", "My prompt")));
    }

    private void expectReply(String response) {
        server.expect(requestTo("http://gemini.test/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(header("X-Worker-Api-Key", "test-worker-key"))
                .andExpect(jsonPath("$.model").value("configured-model"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value("My prompt"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private String reply(String usage) {
        return "{\"model\":\"gemini-actual\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\" Answer \"}}]"
                + (usage == null ? "" : ",\"usage\":" + usage) + "}";
    }
}
