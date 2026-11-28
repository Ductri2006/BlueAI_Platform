package com.blueai.rag.chat.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.quota.AiTokenQuotaService;
import com.blueai.rag.chat.RagChatProperties;
import com.blueai.system.service.GeminiUsageService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class RagCompletionNoRetryTest {
    enum QuotaMode { ABSENT, DISABLED, RESERVED }

    @ParameterizedTest
    @EnumSource(QuotaMode.class)
    void rateLimitsAreTerminalAndKeepRateLimitMetadata(QuotaMode mode) {
        var fixture = new Fixture(mode);
        fixture.server.expect(requestTo(Fixture.URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(fixture::ask).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getCode()).isEqualTo("AI_PROVIDER_RATE_LIMITED");
            assertThat(error.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(error.getRetryAfterSeconds()).isEqualTo(60L);
        });
        fixture.verifyOneAttempt();
        if (mode == QuotaMode.RESERVED) verify(fixture.quota).release(fixture.reservation);
    }

    @ParameterizedTest
    @MethodSource("httpTimeouts")
    void requestTimeoutsAreTerminalAndKeepAmbiguousReservations(QuotaMode mode, HttpStatus status) {
        var fixture = new Fixture(mode);
        fixture.server.expect(requestTo(Fixture.URL)).andRespond(withStatus(status));
        assertThatThrownBy(fixture::ask).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("LLM_REQUEST_FAILED"));
        fixture.verifyOneAttempt();
        fixture.verifyNoSettlement();
    }

    static Stream<Arguments> httpTimeouts() {
        return Arrays.stream(QuotaMode.values()).flatMap(mode -> Stream.of(
                HttpStatus.REQUEST_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT).map(status -> Arguments.of(mode, status)));
    }

    @ParameterizedTest
    @MethodSource("transportFailures")
    void transportFailuresAreNeverReplayed(QuotaMode mode, IOException failure) {
        var fixture = new Fixture(mode);
        fixture.server.expect(requestTo(Fixture.URL)).andRespond(request -> { throw failure; });
        assertThatThrownBy(fixture::ask).isInstanceOf(ApiException.class);
        fixture.verifyOneAttempt();
        fixture.verifyNoSettlement();
    }

    static Stream<Arguments> transportFailures() {
        return Arrays.stream(QuotaMode.values()).flatMap(mode -> Stream.of(
                new SocketTimeoutException("read timed out after dispatch"),
                new SocketException("connection reset after dispatch"),
                new ConnectException("connect timed out"))
                .map(failure -> Arguments.of(mode, failure)));
    }

    @ParameterizedTest
    @EnumSource(QuotaMode.class)
    void uncheckedIoFailuresAreAlsoTerminal(QuotaMode mode) {
        var fixture = new Fixture(mode);
        fixture.server.expect(requestTo(Fixture.URL)).andRespond(request -> {
            throw new UncheckedIOException(new SocketTimeoutException("ambiguous provider timeout"));
        });
        assertThatThrownBy(fixture::ask).isInstanceOf(ApiException.class);
        fixture.verifyOneAttempt();
        fixture.verifyNoSettlement();
    }

    private static final class Fixture {
        static final String URL = "http://completion.test/v1/chat/completions";
        final RagRestClientFactory factory = mock(RagRestClientFactory.class);
        final GeminiUsageService usage = mock(GeminiUsageService.class);
        final AiTokenQuotaService quota = mock(AiTokenQuotaService.class);
        final AiTokenQuotaService.Reservation reservation = new AiTokenQuotaService.Reservation("operation", 4096L, "test-model");
        final MockRestServiceServer server;
        final RagCompletionClient client;

        Fixture(QuotaMode mode) {
            var config = new RagChatProperties();
            config.setLlmBaseUrl("http://completion.test");
            config.setLlmApiKey("test-key");
            config.setLlmModel("test-model");
            config.setLlmMaxAttempts(5);
            var builder = RestClient.builder();
            server = MockRestServiceServer.bindTo(builder).build();
            when(factory.create()).thenAnswer(call -> builder.clone());
            client = new RagCompletionClient(config, factory, usage, "test-worker", URI.create("http://completion.test"));
            if (mode != QuotaMode.ABSENT) {
                when(quota.maxOutputTokens()).thenReturn(2048);
                when(quota.reserve(anyString(), anyLong(), eq(2048)))
                        .thenReturn(mode == QuotaMode.RESERVED ? reservation : null);
                client.setTokenQuota(quota);
            }
        }

        String ask() { return client.complete(List.of(new LlmChatMessage("user", "test prompt"))); }

        void verifyOneAttempt() {
            server.verify();
            verify(factory, times(1)).create();
            verifyNoInteractions(usage);
        }

        void verifyNoSettlement() {
            verify(quota, never()).release(any());
            verify(quota, never()).confirm(any(), anyLong(), anyLong(), anyLong());
        }
    }
}
