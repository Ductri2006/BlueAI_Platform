package com.blueai.auth.exception;

import com.blueai.rag.chat.service.BilingualReplyService;
import com.blueai.rag.chat.service.ReplyLanguagePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void requestBoundChineseLocalizesQuotaErrorsWithoutChangingStatusCodeOrRetryAfter() {
        var request = new MockHttpServletRequest("POST", "/api/rag/ask");
        var previous = RequestContextHolder.getRequestAttributes();
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            BilingualReplyService.bindLanguage(new ReplyLanguagePolicy.State("zh-CN", false));
            assertThat(request.getAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE)).isEqualTo("zh-CN");
            var exception = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_QUOTA_EXHAUSTED", "Đã hết hạn mức AI.", 73L);
            var response = handler.handleApiException(exception, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("73");
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().status()).isEqualTo(429);
            assertThat(response.getBody().code()).isEqualTo(exception.getCode());
            assertThat(response.getBody().message()).isEqualTo("您的 AI 使用额度已用完。请在额度重置后重试。");
            assertThat(response.getBody().path()).isEqualTo("/api/rag/ask");
            assertThat(response.getBody().fieldErrors()).isEmpty();
        } finally {
            RequestContextHolder.setRequestAttributes(previous);
        }
    }

    @Test
    void chineseRuntimeRateLimitsRetainDefaultMinimumAndExplicitRetryAfter() {
        var request = new MockHttpServletRequest();
        request.setAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE, "zh-CN");
        for (String code : List.of("AI_PROJECT_RATE_LIMITED", "AI_PROVIDER_RATE_LIMITED", "SESSION_TURN_LIMIT", "WIDGET_RATE_LIMITED", "RAG_RATE_LIMITED")) {
            for (Long retryAfter : List.of(-5L, 0L, 1L, 120L)) {
                var response = handler.handleApiException(new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, "original", retryAfter), request);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo(Long.toString(Math.max(1L, retryAfter)));
                assertThat(response.getBody().code()).isEqualTo(code);
                assertThat(response.getBody().message()).isNotEqualTo("original");
                assertThat(ReplyLanguagePolicy.detect(response.getBody().message())).isEqualTo("zh-CN");
            }
            var defaultRetry = handler.handleApiException(new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, "original"), request);
            assertThat(defaultRetry.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        }
    }

    @Test
    void chineseNonRateErrorsRetainStatusAndDoNotAddRetryAfter() {
        var request = new MockHttpServletRequest();
        request.setAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE, "zh-CN");
        for (var entry : Map.of("AI_REQUEST_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE, "REPLY_TRANSLATION_FAILED", HttpStatus.BAD_GATEWAY).entrySet()) {
            var response = handler.handleApiException(new ApiException(entry.getValue(), entry.getKey(), "original", 42L), request);
            assertThat(response.getStatusCode()).isEqualTo(entry.getValue());
            assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.RETRY_AFTER);
            assertThat(response.getBody().code()).isEqualTo(entry.getKey());
            assertThat(ReplyLanguagePolicy.detect(response.getBody().message())).isEqualTo("zh-CN");
        }
    }

    @Test
    void englishErrorLegacyAndVietnameseOrMissingLanguageArePreserved() {
        var exception = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_TOKEN_QUOTA_EXHAUSTED", "Đã hết hạn mức AI.", 73L);
        var request = new MockHttpServletRequest();
        assertThat(handler.handleApiException(exception, request).getBody().message()).isEqualTo(exception.getMessage());
        for (Object language : List.of("vi", "fr", 123)) {
            request.setAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE, language);
            assertThat(handler.handleApiException(exception, request).getBody().message()).isEqualTo(exception.getMessage());
        }
        request.setAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE, "en");
        assertThat(handler.handleApiException(exception, request).getBody().message())
                .isEqualTo(BilingualReplyService.englishError(exception.getCode(), exception.getMessage()))
                .isEqualTo("Your AI usage limit has been reached. Please try again after the limit resets.");
        assertThat(BilingualReplyService.localizedError(exception.getCode(), exception.getMessage(), null)).isEqualTo(exception.getMessage());
    }

    @Test
    void unknownErrorsArePreservedInEveryLanguage() {
        for (String language : List.of("vi", "en", "zh-CN")) {
            var request = new MockHttpServletRequest();
            request.setAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE, language);
            var exception = new ApiException(HttpStatus.CONFLICT, "CUSTOM_ERROR", "original", 42L);
            var response = handler.handleApiException(exception, request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.RETRY_AFTER);
            assertThat(response.getBody().message()).isEqualTo(exception.getMessage());
        }
    }
}
