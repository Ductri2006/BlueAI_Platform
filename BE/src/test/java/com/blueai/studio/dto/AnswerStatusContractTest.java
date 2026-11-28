package com.blueai.studio.dto;

import com.blueai.rag.chat.dto.ConversationContextSourceResponse;
import com.blueai.rag.chat.dto.RagAnswerResponse;
import com.blueai.rag.chat.dto.RagSourceResponse;
import com.blueai.rag.conversation.dto.ConversationMessageResponse;
import com.blueai.rag.conversation.model.AnswerOrigin;
import com.blueai.rag.conversation.model.AnswerStatus;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.MessageEvidence;
import com.blueai.rag.conversation.model.MessageEvidenceConverter;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.studio.dto.BotStudioDtos.DialogTrace;
import com.blueai.studio.dto.BotStudioDtos.PublicRuntimeOutput;
import com.blueai.studio.dto.BotStudioDtos.RuntimeMessageResponse;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerStatusContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RagSourceResponse document = new RagSourceResponse("doc", "Knowledge.csv", "chunk", 0.9,
            "Verified facts", "ENTERPRISE_DOCUMENT", "READY");
    private final ConversationContextSourceResponse context = new ConversationContextSourceResponse(
            "USER_MEMORY", null, "Customer preference", "travel");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyJsonDefaultsToAnsweredWithoutGuessingFromTheOldFallbackFlag(boolean fallback) throws Exception {
        String json = "{\"message\":\"Legacy reply\",\"fallback\":" + fallback + "}";
        assertThat(mapper.readValue(json, RuntimeResponse.class).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(mapper.readValue(json, PublicRuntimeOutput.class).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(mapper.readValue(json.substring(0, json.length() - 1) + ",\"answerStatus\":null}",
                PublicRuntimeOutput.class).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        String history = "{\"id\":\"old\",\"role\":\"ASSISTANT\",\"content\":\"Legacy reply\","
                + "\"evidence\":{\"sources\":[],\"contextSources\":[],\"origin\":\"CORE\"}}";
        assertThat(mapper.readValue(history, ConversationMessageResponse.class).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
    }

    @Test
    void compatibilityConstructorsContinueToDefaultToAnswered() {
        assertThat(new RuntimeResponse("Reply", null, Map.of(), null, null, false).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(new RuntimeResponse("Reply", null, Map.of(), null, null, false, true).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(new RuntimeResponse("Reply", null, Map.of(), null, null, false, true, null, List.of(), List.of())
                .answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(new PublicRuntimeOutput("Reply", false).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(new ConversationMessageResponse("id", MessageRole.ASSISTANT, "Reply", Instant.EPOCH, null)
                .answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
    }

    @ParameterizedTest
    @EnumSource(AnswerStatus.class)
    void responseCopiesAndPublicProjectionPreserveExplicitDeliveryStatus(AnswerStatus status) throws Exception {
        boolean fallback = status == AnswerStatus.SAFE_FALLBACK || status == AnswerStatus.INSUFFICIENT_EVIDENCE;
        var original = new RuntimeResponse("Reply", "intent", Map.of("country", "JP"), "node", null, fallback, true)
                .withAnswerStatus(status);
        var trace = new DialogTrace("CORE", "Internal routing detail", List.of("node"), Map.of(), null);
        for (RuntimeResponse copy : List.of(
                original.withTrace(trace).withEvidence(List.of(document), List.of(context)),
                original.withEvidence(List.of(document), List.of(context)).withTrace(trace))) {
            assertThat(copy.answerStatus()).isEqualTo(status);
            assertThat(copy.fallback()).isEqualTo(fallback);
            assertThat(copy.dialogHandled()).isTrue();
            assertThat(copy.trace()).isSameAs(trace);
            assertThat(copy.sources()).containsExactly(document);
            assertThat(copy.contextSources()).containsExactly(context);
            var published = PublicRuntimeOutput.from(copy);
            assertThat(published.answerStatus()).isEqualTo(status);
            assertThat(published.fallback()).isEqualTo(fallback);
            var tree = mapper.valueToTree(published);
            List<String> fields = new ArrayList<>();
            tree.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("message", "fallback", "answerStatus");
            assertThat(tree.path("answerStatus").asText()).isEqualTo(status.name());
            assertThat(mapper.readValue(mapper.writeValueAsString(published), PublicRuntimeOutput.class)).isEqualTo(published);
            var envelope = new RuntimeMessageResponse("request", "session", "release", 1L, published);
            assertThat(mapper.valueToTree(envelope).path("result").path("answerStatus").asText()).isEqualTo(status.name());
        }
        assertThat(original.trace()).isNull();
        assertThat(original.sources()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(AnswerStatus.class)
    void historyAndChatJsonExposeStatusBesideUnchangedEvidence(AnswerStatus status) throws Exception {
        var evidence = new MessageEvidence(List.of(document), List.of(context), AnswerOrigin.CORE);
        var entity = new ConversationMessage(null, MessageRole.ASSISTANT, "Reply", evidence);
        entity.setAnswerStatus(status);
        var response = ConversationMessageResponse.from(entity);
        assertThat(response.answerStatus()).isEqualTo(status);
        assertThat(response.evidence()).isSameAs(evidence);
        var chat = new RagAnswerResponse(null, response, List.of(document), List.of(context));
        var tree = mapper.valueToTree(chat).path("assistantMessage");
        assertThat(tree.path("answerStatus").asText()).isEqualTo(status.name());
        assertThat(tree.path("evidence")).isEqualTo(mapper.valueToTree(evidence));
        assertThat(tree.path("evidence").has("answerStatus")).isFalse();
        String stored = new MessageEvidenceConverter().convertToDatabaseColumn(evidence);
        // A strict reader with the previous evidence shape remains able to deserialize this JSON.
        LegacyEvidence legacy = mapper.readValue(stored, LegacyEvidence.class);
        assertThat(legacy.sources()).containsExactly(document);
        assertThat(legacy.contextSources()).containsExactly(context);
        assertThat(legacy.origin()).isEqualTo(AnswerOrigin.CORE);
        assertThat(mapper.readTree(stored).size()).isEqualTo(3);
    }

    @Test
    void nullStatusNormalizesWithoutChangingEvidenceOrOrigin() {
        var evidence = new MessageEvidence(List.of(), List.of(), AnswerOrigin.DIALOG);
        var entity = new ConversationMessage(null, MessageRole.ASSISTANT, "Reply", evidence);
        entity.setAnswerStatus(null);
        assertThat(ConversationMessageResponse.from(entity).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
        assertThat(entity.getEvidence()).isSameAs(evidence);
        assertThat(new RuntimeResponse("Reply", null, Map.of(), null, null, true)
                .withAnswerStatus(null).answerStatus()).isEqualTo(AnswerStatus.ANSWERED);
    }

    record LegacyEvidence(List<RagSourceResponse> sources, List<ConversationContextSourceResponse> contextSources,
                          AnswerOrigin origin) { }
}
