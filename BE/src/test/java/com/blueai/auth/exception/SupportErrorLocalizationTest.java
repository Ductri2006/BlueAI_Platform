package com.blueai.auth.exception;

import com.blueai.rag.chat.service.BilingualReplyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SupportErrorLocalizationTest {
    // Entirely synthetic upstream text: none of it may reach the customer response.
    private static final String INTERNAL = "Set APP_LLM_API_KEY and APP_RAG_RETRIEVAL_BASE_URL; "
            + "Authorization: Bearer synthetic-private-value; allowlist 192.0.2.10; http://private-worker.invalid";
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    static Stream<Arguments> customerFailures() {
        return Stream.of(
                "RAG_RETRIEVAL_FAILED", "RAG_RETRIEVER_NOT_CONFIGURED",
                "AI_RETRIEVAL_FAILED", "AI_RETRIEVAL_NOT_CONFIGURED",
                "LLM_UNAVAILABLE", "LLM_NOT_CONFIGURED", "LLM_REQUEST_FAILED", "LLM_INVALID_RESPONSE",
                "LLM_RESPONSE_LANGUAGE_MISMATCH", "REPLY_TRANSLATION_FAILED",
                "AI_PROVIDER_NOT_CONFIGURED", "AI_PROVIDER_FAILED", "AI_PROVIDER_CAPACITY", "AI_INVALID_OUTPUT",
                "PRODUCT_UPSTREAM_IP_FORBIDDEN", "PRODUCT_UPSTREAM_UNAUTHORIZED", "PRODUCT_SYNC_FAILED",
                "PRODUCT_CATALOG_INCOMPLETE", "PRODUCT_CATALOG_UNAVAILABLE", "PRODUCT_CATALOG_EMPTY", "PRODUCT_SOURCE_CHANGED")
                .flatMap(code -> Arrays.asList("vi", "en", "zh-CN", null).stream().map(language -> Arguments.of(code, language)));
    }

    @ParameterizedTest
    @MethodSource("customerFailures")
    void supportFailuresUseSafeLocalizedMessagesAndPreserveTheErrorContract(String code, String language) {
        var request = new MockHttpServletRequest("POST", "/api/rag/ask");
        if (language != null) request.setAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE, language);
        var response = handler.handleApiException(new ApiException(HttpStatus.BAD_GATEWAY, code, INTERNAL, 41L), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getHeaders()).doesNotContainKey(HttpHeaders.RETRY_AFTER);
        var body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(code);
        assertThat(body.status()).isEqualTo(502);
        assertThat(body.path()).isEqualTo("/api/rag/ask");
        assertThat(body.fieldErrors()).isEmpty();
        assertThat(body.message()).isNotBlank().isNotEqualTo(INTERNAL)
                .doesNotContain("APP_", "Authorization", "Bearer", "synthetic-private-value", "192.0.2.10",
                        "private-worker", "http://", "API_KEY", "allowlist");
        if ("en".equals(language)) assertThat(body.message()).startsWith("I ").contains("contact support");
        else if ("zh-CN".equals(language)) assertThat(body.message()).startsWith("暂时").contains("人工客服");
        else assertThat(body.message()).startsWith("Hiện").contains("nhân viên hỗ trợ");
    }

    @Test
    void rateLimitStatusAndRetryMetadataRemainIndependentOfLocalization() {
        for (String language : List.of("en", "zh-CN")) {
            var request = new MockHttpServletRequest("POST", "/api/rag/ask");
            request.setAttribute(BilingualReplyService.LANGUAGE_ATTRIBUTE, language);
            var response = handler.handleApiException(new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "AI_PROVIDER_RATE_LIMITED", INTERNAL, 73L), request);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("73");
            assertThat(response.getBody().code()).isEqualTo("AI_PROVIDER_RATE_LIMITED");
            assertThat(response.getBody().message()).doesNotContain("APP_", "synthetic-private-value");
        }
    }

    @Test
    void unmappedBusinessErrorsAndExistingVietnameseQuotaMessagesKeepTheirMeaning() {
        for (String language : Arrays.asList("vi", "en", "zh-CN", null)) {
            assertThat(BilingualReplyService.localizedError("SESSION_NOT_FOUND", "Conversation not found.", language))
                    .isEqualTo("Conversation not found.");
        }
        assertThat(BilingualReplyService.localizedError("AI_TOKEN_QUOTA_EXHAUSTED", "Đã hết hạn mức AI.", "vi"))
                .isEqualTo("Đã hết hạn mức AI.");
    }
}
