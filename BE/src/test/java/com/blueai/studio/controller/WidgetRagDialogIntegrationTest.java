package com.blueai.studio.controller;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.dto.AskQuestionRequest;
import com.blueai.rag.chat.service.*;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.studio.dto.BotStudioDtos.*;
import com.blueai.studio.model.BotCreationTemplate;
import com.blueai.studio.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.rag.retrieval-base-url=http://widget-rag.test",
        "app.widget.allowed-origins=https://shop.example.test"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WidgetRagDialogIntegrationTest {
    @Autowired UserRepository users;
    @Autowired RagDocumentRepository documents;
    @Autowired ConversationRepository conversations;
    @Autowired BotStudioService studio;
    @Autowired BotRuntimeService runtime;
    @Autowired RagChatService rag;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean RagRestClientFactory clientFactory;
    @MockitoBean RagCompletionClient completion;
    MockRestServiceServer retrieval;
    User owner;
    RagDocument publicDocument;
    RagDocument privateDocument;
    AtomicReference<List<LlmChatMessage>> prompt;

    @BeforeEach
    void setUp() {
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "test-unused-hash", "Widget admin", UserRole.ADMIN));
        publicDocument = document(owner, "Public FAQ", "PUBLIC_RETURN_POLICY");
        privateDocument = document(owner, "Private internal", "PRIVATE_SALARY_SECRET");
        RestClient.Builder builder = RestClient.builder();
        retrieval = MockRestServiceServer.bindTo(builder).build();
        when(clientFactory.create()).thenAnswer(invocation -> builder.clone());
        prompt = new AtomicReference<>();
        when(completion.complete(anyList())).thenAnswer(invocation -> {
            prompt.set(List.copyOf(invocation.getArgument(0)));
            return "RAG: đổi trả trong 7 ngày theo FAQ công khai.";
        });
    }

    RagDocument document(User user, String filename, String key) {
        var document = new RagDocument(user, filename, UUID.randomUUID() + key, "text/plain", 100, 500, 50);
        document.markReady(1);
        return documents.saveAndFlush(document);
    }

    BotConfigurationResponse create() {
        return studio.create(owner.getId(), new CreateBotRequest("Hybrid " + UUID.randomUUID(), "Demo", "vi-VN",
                "Asia/Ho_Chi_Minh", true, BotCreationTemplate.RAG_DIALOG_DEMO));
    }

    BotConfigurationResponse configure(BotConfigurationResponse configuration, boolean dialog, boolean core, List<String> ids) {
        var bot = configuration.bot();
        return studio.save(owner.getId(), bot.id(), new SaveConfigurationRequest(bot.draftRevision(), bot.name(),
                bot.description(), bot.locale(), bot.timezone(), core, bot.customInstructions(), configuration.intents(),
                configuration.entities(), configuration.dialogNodes(), configuration.customization(), dialog, ids));
    }

    CreateWidgetSessionResponse session(BotConfigurationResponse configuration) {
        studio.publish(owner.getId(), configuration.bot().id());
        return runtime.createSession(configuration.bot().publicKey(), "127.0.0.77");
    }

    RuntimeMessageResponse message(CreateWidgetSessionResponse session, String question, String requestId) {
        return runtime.message(session.sessionId(), session.accessToken(), new RuntimeMessageRequest(requestId, question), "127.0.0.77");
    }

    void expectRetrieval(boolean publicOnly) {
        var expected = retrieval.expect(requestTo("http://widget-rag.test/v1/retrieval/search"))
                .andExpect(header("X-Worker-Api-Key", "test-worker-api-key-for-integration-tests"));
        if (publicOnly) expected.andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.allowedDocumentIds[0]").value(publicDocument.getId()))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.allowedDocumentIds.length()").value(1));
        expected.andRespond(withSuccess("""
                {"matches":[
                  {"documentId":"%s","chunkId":"public-1","content":"PUBLIC_RETURN_POLICY: đổi trả trong 7 ngày.","score":0.9},
                  {"documentId":"%s","chunkId":"private-1","content":"PRIVATE_SALARY_SECRET","score":0.99}
                ]}
                """.formatted(publicDocument.getId(), privateDocument.getId()), MediaType.APPLICATION_JSON));
    }

    @Test
    void hybridTemplateUsesDialogFirstAndRagForUnmatchedQuestionsWithOnlyPublicKnowledge() {
        var configuration = configure(create(), true, true, List.of(publicDocument.getId()));
        assertThat(configuration.bot().dialogEnabled()).isTrue();
        assertThat(configuration.bot().blueAiCoreEnabled()).isTrue();
        var session = session(configuration);
        assertThat(message(session, "xin chào", "greeting").result().message()).contains("Sản phẩm A/B");
        verifyNoInteractions(completion);
        expectRetrieval(true);
        assertThat(message(session, "Điều kiện hoàn trả hàng lỗi là gì?", "knowledge").result().message()).startsWith("RAG:");
        assertThat(prompt.get().getFirst().content()).contains("PUBLIC_RETURN_POLICY").doesNotContain("PRIVATE_SALARY_SECRET");
        assertThat(prompt.get()).anyMatch(item -> item.role().equals("user") && item.content().equals("xin chào"));
        retrieval.verify();
    }

    @Test
    void ragOnlyBypassesMatchingDialogAndPendingSlots() {
        var session = session(configure(create(), false, true, List.of(publicDocument.getId())));
        assertThat(session.welcome().message()).doesNotContain("DEMO SALES");
        expectRetrieval(true);
        assertThat(message(session, "xin chào", "rag-only").result().message()).startsWith("RAG:");
        retrieval.verify();
    }

    @Test
    void dialogOnlyNeverCallsProvidersIncludingUnmatchedQuestions() {
        var session = session(configure(create(), true, false, List.of(publicDocument.getId())));
        assertThat(message(session, "Điều kiện hoàn trả hàng lỗi là gì?", "dialog-only").result().fallback()).isTrue();
        verifyNoInteractions(completion, clientFactory);
    }

    @Test
    void hybridDoesNotSendPendingDialogSlotsToRag() {
        var session = session(configure(create(), true, true, List.of(publicDocument.getId())));
        assertThat(message(session, "giờ hoạt động", "missing").result().message()).contains("Hà Nội");
        assertThat(message(session, "Hà Nội", "slot").result().message()).contains("08:00–21:00");
        verifyNoInteractions(completion, clientFactory);
    }

    @Test
    void pinnedReleaseSurvivesModeChangesAndRetryDoesNotGenerateTwice() {
        var configuration = configure(create(), false, true, List.of(publicDocument.getId()));
        var existing = session(configuration);
        var newConfiguration = configure(configuration, true, false, List.of(publicDocument.getId()));
        var newest = session(newConfiguration);
        expectRetrieval(true);
        var result = message(existing, "Điều kiện hoàn trả hàng lỗi là gì?", "idempotent");
        assertThat(message(existing, "Điều kiện hoàn trả hàng lỗi là gì?", "idempotent")).isEqualTo(result);
        assertThat(message(newest, "Điều kiện hoàn trả hàng lỗi là gì?", "new-dialog").result().fallback()).isTrue();
        verify(completion, times(1)).complete(anyList());
        retrieval.verify();
    }

    @Test
    void noPublicKnowledgeAndDeletedKnowledgeFailClosed() {
        var empty = session(configure(create(), false, true, List.of()));
        assertThat(message(empty, "xem chính sách đổi trả", "empty").result().message()).contains("chưa tìm thấy");
        var configured = session(configure(create(), false, true, List.of(publicDocument.getId())));
        documents.delete(publicDocument); documents.flush();
        assertThat(message(configured, "xem chính sách đổi trả", "removed").result().message()).contains("chưa tìm thấy");
        verifyNoInteractions(completion, clientFactory);
    }

    @Test
    void rejectsBothModesOffAndPublicDocumentsBelongingToAnotherAdmin() {
        var configuration = create();
        assertThatThrownBy(() -> configure(configuration, false, false, List.of())).isInstanceOf(ApiException.class).hasMessageContaining("RAG hoặc Dialog");
        User other = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "unused", "Other admin", UserRole.ADMIN));
        var foreign = document(other, "Other admin KB", "OTHER_OWNER");
        assertThatThrownBy(() -> configure(configuration, true, true, List.of(foreign.getId())))
                .isInstanceOf(ApiException.class).hasMessageContaining("thuộc tài khoản");
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false"})
    void sameModeControlsStudioPreviewAndAuthenticatedChat(boolean dialog, boolean core) {
        var configuration = configure(create(), dialog, core, List.of());
        if (!dialog) { expectRetrieval(false); expectRetrieval(false); }
        var preview = studio.preview(owner.getId(), configuration.bot().id(), new PreviewRequest("xin chào", null));
        assertThat(preview.message().startsWith("RAG:")).isEqualTo(!dialog);
        studio.publish(owner.getId(), configuration.bot().id());
        studio.activate(owner.getId(), configuration.bot().id());
        var conversation = conversations.saveAndFlush(new Conversation(owner, "Mode check"));
        var answer = rag.ask(owner.getId(), conversation.getId(), new AskQuestionRequest("xin chào"));
        assertThat(answer.assistantMessage().content().startsWith("RAG:")).isEqualTo(!dialog);
        retrieval.verify();
    }

    @Test
    void widgetHistoryBelongsOnlyToItsOwnVisitorSession() {
        var configuration = configure(create(), false, true, List.of(publicDocument.getId()));
        var first = session(configuration);
        var second = runtime.createSession(configuration.bot().publicKey(), "127.0.0.78");
        expectRetrieval(true); expectRetrieval(true);
        message(first, "CLIENT_A_PERSONAL_NOTE", "visitor-a");
        message(second, "Điều kiện đổi trả là gì?", "visitor-b");
        assertThat(prompt.get()).noneMatch(item -> item.content().contains("CLIENT_A_PERSONAL_NOTE"));
        retrieval.verify();
    }

    @Test
    void thirdPartyWebsiteUsesPublicSessionAndReceivesOnlyPublicOutput() throws Exception {
        var configuration = configure(create(), false, true, List.of(publicDocument.getId()));
        studio.publish(owner.getId(), configuration.bot().id());
        var created = mvc.perform(post("/api/v1/widget/" + configuration.bot().publicKey() + "/sessions")
                        .header("Origin", "https://shop.example.test"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var session = mapper.readTree(created.getContentAsString());
        expectRetrieval(true);
        mvc.perform(post("/api/v1/runtime/sessions/" + session.get("sessionId").asText() + "/messages")
                        .header("Origin", "https://shop.example.test").header("X-Widget-Token", session.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"embed\",\"message\":\"Điều kiện hoàn trả hàng lỗi là gì?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.message").value("RAG: đổi trả trong 7 ngày theo FAQ công khai."))
                .andExpect(jsonPath("$.result.context").doesNotExist()).andExpect(jsonPath("$.result.trace").doesNotExist());
        retrieval.verify();
    }
}
