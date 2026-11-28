package com.blueai.rag.chat.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.rag.chat.dto.RagSourceResponse;
import com.blueai.rag.conversation.model.AnswerStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GroundedReplyComposerTest {
    private static final ReplyLanguagePolicy.State CHINESE = new ReplyLanguagePolicy.State("zh-CN", false);
    private static final String WRONG = "Please enable data roaming on your phone.";
    private static final String CHINESE_ANSWER = "请开启数据漫游。";
    private static final String FACTUAL_CANDIDATE = "Please use TEST-JP-7D at vmobile.jp for 10 GB in 7 days.";
    private static final Pattern MARKER = Pattern.compile("__BLUEAI_FACT_[a-f0-9]{32}_\\d+__");
    private static final List<LlmChatMessage> MESSAGES = List.of(
            new LlmChatMessage("system", "Original grounding instructions and evidence."),
            new LlmChatMessage("user", "Original question: how do I enable roaming?"));
    private static final List<RagSourceResponse> FACTUAL_EVIDENCE = List.of(source(
            "USER_DOCUMENT", "setup.txt", "TEST-JP-7D; APN vmobile.jp; 10 GB; validity 7 days."));

    private final RagCompletionClient completion = mock(RagCompletionClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GroundedReplyComposer composer = new GroundedReplyComposer(completion, mapper);

    @Test
    void matchingProseUsesOnlyTheInitialCompletion() {
        when(completion.complete(MESSAGES)).thenReturn(CHINESE_ANSWER);

        var reply = composer.compose(MESSAGES, CHINESE, List.of());

        assertThat(reply).isEqualTo(GroundedReplyComposer.Reply.answered(CHINESE_ANSWER));
        verify(completion).complete(MESSAGES);
        verifyNoMoreInteractions(completion);
    }

    @Test
    void authorizedLongProductTitleDoesNotOutvoteChineseProse() {
        String title = "Bluecom Japan Unlimited Premium International Travel";
        String text = "请选择" + title + "。该套餐适合您的旅行。";
        when(completion.complete(MESSAGES)).thenReturn(text);

        var reply = composer.compose(MESSAGES, CHINESE, List.of(source("PRODUCT_CATALOG", title, null)));

        assertThat(reply.text()).isEqualTo(text);
        assertThat(reply.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(reply.repaired()).isFalse();
        verify(completion).complete(MESSAGES);
        verifyNoMoreInteractions(completion);
    }

    @Test
    void uncertainBareTitleDoesNotTriggerPaidRepair() {
        String title = "Bluecom Japan Unlimited Premium";
        when(completion.complete(MESSAGES)).thenReturn(title);

        assertThat(composer.compose(MESSAGES, CHINESE, List.of()).text()).isEqualTo(title);

        verify(completion).complete(MESSAGES);
        verifyNoMoreInteractions(completion);
    }

    static Stream<Arguments> wrongLanguageCases() {
        var english = new ReplyLanguagePolicy.State("en", false);
        var vietnamese = new ReplyLanguagePolicy.State("vi", false);
        String vi = "Bạn hãy bật chuyển vùng dữ liệu trên điện thoại.";
        return Stream.of(
                Arguments.of(CHINESE, WRONG, CHINESE_ANSWER),
                Arguments.of(CHINESE, vi, CHINESE_ANSWER),
                Arguments.of(CHINESE, "请看 `Please enable data roaming on your phone.`。", CHINESE_ANSWER),
                Arguments.of(CHINESE, "请关闭 `Chế độ máy bay` 后开启数据漫游。", CHINESE_ANSWER),
                Arguments.of(english, vi, WRONG),
                Arguments.of(vietnamese, WRONG, vi));
    }

    @ParameterizedTest
    @MethodSource("wrongLanguageCases")
    void genuineWrongProseReceivesExactlyOneRepair(ReplyLanguagePolicy.State language, String candidate,
                                                  String translation) throws Exception {
        when(completion.complete(anyList())).thenReturn(candidate, json(translation));

        var reply = composer.compose(MESSAGES, language, List.of());

        assertThat(reply).isEqualTo(new GroundedReplyComposer.Reply(translation, AnswerStatus.ANSWERED, true));
        verify(completion, times(2)).complete(anyList());
        verifyNoMoreInteractions(completion);
    }

    @Test
    void repairHasOnlyTheCandidateAndTranslationRulesNotOriginalQuestionsOrSources() throws Exception {
        when(completion.complete(anyList())).thenReturn(WRONG, json(CHINESE_ANSWER));

        composer.compose(MESSAGES, CHINESE, List.of(source("USER_DOCUMENT", "guide.txt", "Secret extra evidence.")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmChatMessage>> calls = ArgumentCaptor.forClass(List.class);
        verify(completion, times(2)).complete(calls.capture());
        var repair = calls.getAllValues().get(1);
        assertThat(repair).hasSize(2);
        assertThat(repair.getFirst().role()).isEqualTo("system");
        assertThat(repair.getFirst().content()).contains("LANGUAGE REPAIR ONLY", "Do not add facts", "negations");
        assertThat(repair.get(1).role()).isEqualTo("user");
        assertThat(maskedCandidate(repair)).isEqualTo(WRONG);
        assertThat(repair.toString()).doesNotContain("Original grounding", "Original question", "Secret extra evidence");
    }

    @Test
    void proseDeclaredAsANameInAnExcerptCannotExemptWrongLanguage() throws Exception {
        when(completion.complete(anyList())).thenReturn(WRONG, json(CHINESE_ANSWER));
        var evidence = List.of(source("USER_DOCUMENT", "guide.txt", "name=" + WRONG));

        var reply = composer.compose(MESSAGES, CHINESE, evidence);

        assertThat(GroundedReplyComposer.authorizedTerms(evidence)).doesNotContain(WRONG);
        assertThat(reply.repaired()).isTrue();
        verify(completion, times(2)).complete(anyList());
    }

    @Test
    void preservesIdentifiersAndQuantitiesWhileAllowingTimeUnitTranslation() throws Exception {
        stubRepair(FACTUAL_CANDIDATE, masked -> {
            var markers = markers(masked);
            assertThat(markers).hasSize(4);
            assertThat(masked).doesNotContain("TEST-JP-7D", "vmobile.jp", "10 GB", "7 days");
            return factualTranslation(markers);
        });

        var reply = composer.compose(MESSAGES, CHINESE, FACTUAL_EVIDENCE);

        assertThat(reply.repaired()).isTrue();
        assertThat(reply.status()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(reply.text()).contains("TEST-JP-7D", "vmobile.jp", "10 GB", "7天内使用")
                .doesNotContain("__BLUEAI_FACT_", "days");
        verify(completion, times(2)).complete(anyList());
    }

    @Test
    void preservesEmailAndLiteralCurrencyWithoutReplacementExpansion() throws Exception {
        String candidate = "Please contact support+qa@example.test; the price is $19.95 USD.";
        var evidence = List.of(source("USER_DOCUMENT", "price.txt", "support+qa@example.test; $19.95 USD"));
        stubRepair(candidate, masked -> {
            var markers = markers(masked);
            assertThat(markers).hasSize(4);
            return "请联系" + markers.get(0) + "，费用为" + markers.get(1) + markers.get(2) + " " + markers.get(3) + "。";
        });

        var reply = composer.compose(MESSAGES, CHINESE, evidence);

        assertThat(reply.repaired()).isTrue();
        assertThat(reply.text()).contains("support+qa@example.test", "$19.95 USD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "reordered", "altered", "foreign", "newAmount", "newSource"})
    void invalidFactEditsFallBackWithoutAnotherCompletion(String edit) throws Exception {
        stubRepair(FACTUAL_CANDIDATE, masked -> {
            var markers = markers(masked);
            String valid = factualTranslation(markers);
            return switch (edit) {
                case "missing" -> valid.replace(markers.getFirst(), "");
                case "duplicate" -> valid + markers.getFirst();
                case "reordered" -> factualTranslation(List.of(markers.get(1), markers.get(0), markers.get(2), markers.get(3)));
                case "altered" -> valid.replace(markers.getFirst(), markers.getFirst().replace("_0__", "_00__"));
                case "foreign" -> valid + "__BLUEAI_FACT_foreign_0__";
                case "newAmount" -> valid + "另送99 GB流量。";
                case "newSource" -> valid + "[S99]";
                default -> throw new AssertionError(edit);
            };
        });

        assertThat(composer.compose(MESSAGES, CHINESE, FACTUAL_EVIDENCE))
                .isEqualTo(GroundedReplyComposer.safeFallback(CHINESE));
        verify(completion, times(2)).complete(anyList());
        verifyNoMoreInteractions(completion);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "{", "null", "[]", "{\"translation\":null}", "{\"translation\":1}",
            "{\"translation\":\"请开启数据漫游。\",\"sources\":[]}",
            "{\"translation\":\"请开启数据漫游。\",\"translation\":\"请开启数据漫游。\"}",
            "{\"translation\":\"请开启数据漫游。\"} {}", "```json\n{\"translation\":\"请开启数据漫游。\"}\n```"})
    void malformedRepairPayloadsFallBackWithoutRetry(String raw) {
        when(completion.complete(anyList())).thenReturn(WRONG).thenReturn(raw);

        assertThat(composer.compose(MESSAGES, CHINESE, List.of()))
                .isEqualTo(GroundedReplyComposer.safeFallback(CHINESE));
        verify(completion, times(2)).complete(anyList());
        verifyNoMoreInteractions(completion);
    }

    @ParameterizedTest
    @ValueSource(strings = {WRONG, "Bluecom Japan Unlimited Premium"})
    void repairMustMatchRatherThanRemainWrongOrUncertain(String translation) throws Exception {
        when(completion.complete(anyList())).thenReturn(WRONG, json(translation));

        assertThat(composer.compose(MESSAGES, CHINESE, List.of()))
                .isEqualTo(GroundedReplyComposer.safeFallback(CHINESE));
        verify(completion, times(2)).complete(anyList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void provider429IsPropagatedUnchangedWithoutRetry(boolean duringRepair) {
        var error = new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_PROVIDER_RATE_LIMITED", "Try later", 60L);
        if (duringRepair) when(completion.complete(anyList())).thenReturn(WRONG).thenThrow(error);
        else when(completion.complete(anyList())).thenThrow(error);

        assertThatThrownBy(() -> composer.compose(MESSAGES, CHINESE, List.of())).isSameAs(error);
        verify(completion, times(duringRepair ? 2 : 1)).complete(anyList());
        verifyNoMoreInteractions(completion);
    }

    @Test
    void nonQuotaRepairFailureFallsBackWithoutRetry() {
        var error = new ApiException(HttpStatus.BAD_GATEWAY, "LLM_REQUEST_FAILED", "Unavailable");
        when(completion.complete(anyList())).thenReturn(WRONG).thenThrow(error);

        assertThat(composer.compose(MESSAGES, CHINESE, List.of()))
                .isEqualTo(GroundedReplyComposer.safeFallback(CHINESE));
        verify(completion, times(2)).complete(anyList());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "请开启__BLUEAI_FACT_forged_0__数据漫游。"})
    void unusableInitialCandidateNeverTriggersRepair(String candidate) {
        when(completion.complete(anyList())).thenReturn(candidate);

        assertThat(composer.compose(MESSAGES, CHINESE, List.of()))
                .isEqualTo(GroundedReplyComposer.safeFallback(CHINESE));
        verify(completion).complete(MESSAGES);
        verifyNoMoreInteractions(completion);
    }

    @Test
    void excessiveCandidateOrFactCountCannotTriggerPaidRepair() {
        String tooLong = WRONG.repeat(500);
        String tooManyFacts = "Please use " + IntStream.range(0, 129).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(" "));
        for (String candidate : List.of(tooLong, tooManyFacts)) {
            var client = mock(RagCompletionClient.class);
            when(client.complete(anyList())).thenReturn(candidate);
            assertThat(new GroundedReplyComposer(client, mapper).compose(MESSAGES, CHINESE, List.of()))
                    .isEqualTo(GroundedReplyComposer.safeFallback(CHINESE));
            verify(client).complete(MESSAGES);
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    void oversizedTranslationCannotEscapeTheRepairBound() throws Exception {
        when(completion.complete(anyList())).thenReturn(WRONG, json(CHINESE_ANSWER.repeat(3000)));

        assertThat(composer.compose(MESSAGES, CHINESE, List.of()))
                .isEqualTo(GroundedReplyComposer.safeFallback(CHINESE));
        verify(completion, times(2)).complete(anyList());
    }

    @Test
    void fallbackAndInsufficientEvidenceKeepDistinctDeliveryStatuses() {
        var fallback = GroundedReplyComposer.safeFallback(CHINESE);
        var insufficient = GroundedReplyComposer.insufficient(CHINESE);
        assertThat(fallback.status()).isEqualTo(AnswerStatus.SAFE_FALLBACK);
        assertThat(insufficient.status()).isEqualTo(AnswerStatus.INSUFFICIENT_EVIDENCE);
        assertThat(fallback.fallback()).isTrue();
        assertThat(insufficient.fallback()).isTrue();
        assertThat(fallback.repaired()).isFalse();
        assertThat(insufficient.repaired()).isFalse();
        verifyNoMoreInteractions(completion);
    }

    private void stubRepair(String candidate, UnaryOperator<String> translation) throws Exception {
        when(completion.complete(anyList())).thenReturn(candidate).thenAnswer(call -> {
            List<LlmChatMessage> messages = call.getArgument(0);
            return json(translation.apply(maskedCandidate(messages)));
        });
    }

    private String maskedCandidate(List<LlmChatMessage> messages) throws Exception {
        return mapper.readTree(messages.getLast().content()).path("untrustedCandidate").asText();
    }

    private String json(String translation) throws Exception {
        return mapper.writeValueAsString(Map.of("translation", translation));
    }

    private static List<String> markers(String text) {
        return MARKER.matcher(text).results().map(match -> match.group()).toList();
    }

    private static String factualTranslation(List<String> markers) {
        return "请使用" + markers.get(0) + "，接入点设置为" + markers.get(1)
                + "。套餐包含" + markers.get(2) + "，可在" + markers.get(3) + "天内使用。";
    }

    private static RagSourceResponse source(String type, String title, String excerpt) {
        return new RagSourceResponse("document", title, "chunk", 1.0, excerpt, type, "READY");
    }
}
