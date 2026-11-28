package com.blueai.studio.controller;

import com.blueai.ai.task.service.PublishedBotApiService;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.LlmChatMessage;
import com.blueai.rag.chat.service.RagCompletionClient;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.studio.dto.BotStudioDtos.*;
import com.blueai.studio.model.BotCreationTemplate;
import com.blueai.studio.model.DialogContextReusePolicy;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import com.blueai.studio.service.BotRuntimeService;
import com.blueai.studio.service.BotStudioService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Spring/H2 routing, persistence and HTTP contracts; only retrieval and completion are mocked.
 * These assertions verify provider requests, not the quality of a live Gemini answer.
 * Legacy English translation retention remains covered by WidgetRagDialogIntegrationTest.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${BLUEAI_CHINESE_QA_DB_URL:jdbc:h2:mem:chinese-chat-integration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE}",
        "spring.datasource.driver-class-name=${BLUEAI_CHINESE_QA_DB_DRIVER:org.h2.Driver}",
        "app.rag.retrieval-base-url=http://chinese-rag.test",
        "app.widget.allowed-origins=https://shop.example.test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChineseChatIntegrationTest {
    private static final String CLIENT_ADDRESS = "127.0.0.89";
    private static final String PUBLIC_EVIDENCE = "PUBLIC_ESIM_GUIDE";
    private static final String PRIVATE_EVIDENCE = "PRIVATE_SALARY_SECRET";
    private static final String CHINESE_ANSWER = "请在手机设置中添加eSIM，并扫描安装二维码。套餐包含10 GB流量，有效期7天。";
    private static final String ENGLISH_ANSWER = "Add the eSIM in your phone settings and scan the QR code. The plan includes 10 GB for 7 days.";
    private static final String VIETNAMESE_ANSWER = "Thêm eSIM trong phần cài đặt điện thoại rồi quét mã QR. Gói có 10 GB trong 7 ngày.";
    private static final String CHINESE_GREETING = "您好！我可以帮助您查询套餐和安装信息。";
    private static final String CHINESE_PLAN = "目的地 {{destination}} 的套餐包含10 GB流量，有效期7天。";
    private static final String CHINESE_MISSING = "请告诉我您的目的地国家，我会帮您查询适用套餐。";
    private static final String CHINESE_IRRELEVANT = "请提供目的地国家，例如日本或越南。";

    @Autowired UserRepository users;
    @Autowired RagDocumentRepository documents;
    @Autowired BotStudioService studio;
    @Autowired BotRuntimeService runtime;
    @Autowired PublishedBotApiService publishedApi;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean RagRestClientFactory clientFactory;
    @MockitoBean RagCompletionClient completion;

    private MockRestServiceServer retrieval;
    private User owner;
    private RagDocument publicDocument;
    private RagDocument privateDocument;
    private List<List<LlmChatMessage>> prompts;

    @BeforeEach
    void setUp() {
        // Every fixture, API key, session and turn participates in this test's rollback transaction.
        owner = users.saveAndFlush(new User(UUID.randomUUID() + "@chinese.test", "unused-hash",
                "Chinese integration admin", UserRole.ADMIN));
        publicDocument = document("Approved public installation guide", PUBLIC_EVIDENCE);
        privateDocument = document("Private internal salaries", PRIVATE_EVIDENCE);
        RestClient.Builder builder = RestClient.builder();
        retrieval = MockRestServiceServer.bindTo(builder).build();
        when(clientFactory.create()).thenAnswer(invocation -> builder.clone());
        prompts = new ArrayList<>();
        when(completion.complete(anyList())).thenAnswer(invocation -> {
            List<LlmChatMessage> messages = List.copyOf(invocation.getArgument(0));
            prompts.add(messages);
            assertThat(messages.getFirst().role()).isEqualTo("system");
            String system = messages.getFirst().content();
            if (system.contains("Reply language for this turn: Simplified Chinese (zh-CN)")) return CHINESE_ANSWER;
            if (system.contains("Reply language for this turn: English (en)")) return ENGLISH_ANSWER;
            if (system.contains("Reply language for this turn: Vietnamese (vi)")) return VIETNAMESE_ANSWER;
            throw new AssertionError("Expected a RAG completion prompt with an explicit supported reply language");
        });
    }

    @AfterEach
    void verifyRetrievalRequests() {
        retrieval.verify();
    }

    @Test
    void chinesePublicRagUsesSimplifiedChinesePromptAndOnlyApprovedKnowledge() throws Exception {
        var session = session(chineseDialog(true));
        String question = "你好，请问如何安装eSIM并连接网络？";
        expectRetrieval(question);

        mvc.perform(post("/api/v1/runtime/sessions/" + session.sessionId() + "/messages")
                        .header("Origin", "https://shop.example.test")
                        .header("X-Widget-Token", session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RuntimeMessageRequest("chinese-question", question))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.sessionId()))
                .andExpect(jsonPath("$.result.message").value(CHINESE_ANSWER))
                .andExpect(jsonPath("$.result.fallback").value(false))
                .andExpect(jsonPath("$.result.context").doesNotExist())
                .andExpect(jsonPath("$.result.trace").doesNotExist())
                .andExpect(jsonPath("$.result.sources").doesNotExist());

        assertPrompt(0, "Simplified Chinese (zh-CN)", question);
        assertLanguage(session.sessionId(), "zh-CN", false);
        verify(completion, times(1)).complete(anyList());
    }

    @Test
    void chineseRequestMakesOneBoundedRepairThenPersistsExplicitSafeFallback() throws Exception {
        var session = session(configure(create(), false, true, List.of(publicDocument.getId())));
        String question = "请用简体中文回答，如何安装eSIM并连接网络？";
        expectRetrieval(question);
        doReturn(ENGLISH_ANSWER).when(completion).complete(anyList());

        var response = message(session, question, "wrong-provider-language");
        assertThat(response.result().fallback()).isTrue();
        assertThat(response.result().answerStatus()).isEqualTo(com.blueai.rag.conversation.model.AnswerStatus.SAFE_FALLBACK);
        assertThat(response.result().message()).contains("请稍后重试").doesNotContain("LLM_RESPONSE_LANGUAGE_MISMATCH", ENGLISH_ANSWER);

        ArgumentCaptor<List<LlmChatMessage>> completionPrompt = ArgumentCaptor.captor();
        verify(completion, times(2)).complete(completionPrompt.capture());
        prompts.add(List.copyOf(completionPrompt.getAllValues().getFirst()));
        assertPrompt(0, "Simplified Chinese (zh-CN)", question);
        assertThat(prompts).hasSize(1);
        verifyNoMoreInteractions(completion);
        verify(clientFactory, times(1)).create();
        assertTurnCount(session.sessionId(), 1);
        assertLanguage(session.sessionId(), "zh-CN", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"好的", "ok", "iPhone 15"})
    void neutralFollowUpsKeepDetectedChinese(String followUp) throws Exception {
        var session = session(configure(create(), false, true, List.of(publicDocument.getId())));
        String question = "请问我的手机无法连接网络，应该如何设置eSIM？";
        expectRetrieval(question, followUp);

        assertThat(message(session, question, "initial").result().message()).isEqualTo(CHINESE_ANSWER);
        assertLanguage(session.sessionId(), "zh-CN", false);
        assertThat(message(session, followUp, "neutral").result().message()).isEqualTo(CHINESE_ANSWER);

        assertPrompt(0, "Simplified Chinese (zh-CN)", question);
        assertPrompt(1, "Simplified Chinese (zh-CN)", followUp);
        assertThat(prompts.get(1)).contains(new LlmChatMessage("user", question),
                new LlmChatMessage("assistant", CHINESE_ANSWER));
        assertLanguage(session.sessionId(), "zh-CN", false);
        assertTurnCount(session.sessionId(), 2);
        verify(completion, times(2)).complete(anyList());
    }

    @Test
    void explicitPreferencesSwitchFromChineseToEnglishToVietnamese() throws Exception {
        var session = session(configure(create(), false, true, List.of(publicDocument.getId())));
        List<String> messages = List.of("请用简体中文回答，如何安装eSIM？", "iPhone 15",
                "请用英语回答，如何激活eSIM？", "Galaxy S24",
                "请用越南语回答，如何查询剩余流量？", "Pixel 9");
        expectRetrieval(messages.toArray(String[]::new));
        List<String> codes = List.of("zh-CN", "zh-CN", "en", "en", "vi", "vi");
        List<String> names = List.of("Simplified Chinese (zh-CN)", "Simplified Chinese (zh-CN)",
                "English (en)", "English (en)", "Vietnamese (vi)", "Vietnamese (vi)");
        List<String> answers = List.of(CHINESE_ANSWER, CHINESE_ANSWER, ENGLISH_ANSWER,
                ENGLISH_ANSWER, VIETNAMESE_ANSWER, VIETNAMESE_ANSWER);

        for (int i = 0; i < messages.size(); i++) {
            assertThat(message(session, messages.get(i), "preference-" + i).result().message()).isEqualTo(answers.get(i));
            assertPrompt(i, names.get(i), messages.get(i));
            assertLanguage(session.sessionId(), codes.get(i), true);
        }
        assertTurnCount(session.sessionId(), 6);
        verify(completion, times(6)).complete(anyList());
    }

    @Test
    void twoLiveSessionsKeepLanguageAndHistoryIsolated() throws Exception {
        var configuration = configure(create(), false, true, List.of(publicDocument.getId()));
        var chinese = session(configuration);
        var vietnamese = runtime.createSession(configuration.bot().publicKey(), "127.0.0.90");
        assertThat(chinese.sessionId()).isNotEqualTo(vietnamese.sessionId());
        assertThat(chinese.accessToken()).isNotEqualTo(vietnamese.accessToken());
        assertThat(chinese.releaseId()).isEqualTo(vietnamese.releaseId());
        String chineseQuestion = "请用简体中文回答。我的手机无法连接网络，应该如何设置？VISITOR_ZH_ONLY";
        String vietnameseQuestion = "Tôi muốn cài đặt eSIM trên điện thoại. VISITOR_VI_ONLY";
        expectRetrieval(chineseQuestion, vietnameseQuestion, "iPhone 15", "Galaxy S24");

        // Both sessions stay live; interleaving preserves rollback isolation without worker-thread commits.
        assertThat(message(chinese, chineseQuestion, "shared-initial").result().message()).isEqualTo(CHINESE_ANSWER);
        assertThat(message(vietnamese, vietnameseQuestion, "shared-initial").result().message()).isEqualTo(VIETNAMESE_ANSWER);
        assertThat(message(chinese, "iPhone 15", "shared-follow-up").result().message()).isEqualTo(CHINESE_ANSWER);
        assertThat(message(vietnamese, "Galaxy S24", "shared-follow-up").result().message()).isEqualTo(VIETNAMESE_ANSWER);

        assertPrompt(2, "Simplified Chinese (zh-CN)", "iPhone 15");
        assertPrompt(3, "Vietnamese (vi)", "Galaxy S24");
        assertThat(prompts.get(2)).contains(new LlmChatMessage("user", chineseQuestion))
                .noneMatch(item -> item.content().contains("VISITOR_VI_ONLY"));
        assertThat(prompts.get(3)).contains(new LlmChatMessage("user", vietnameseQuestion))
                .noneMatch(item -> item.content().contains("VISITOR_ZH_ONLY"));
        assertLanguage(chinese.sessionId(), "zh-CN", true);
        assertLanguage(vietnamese.sessionId(), "vi", false);
        assertThat(state(chinese.sessionId()).path("history").toString()).doesNotContain("VISITOR_VI_ONLY");
        assertThat(state(vietnamese.sessionId()).path("history").toString()).doesNotContain("VISITOR_ZH_ONLY");
        assertTurnCount(chinese.sessionId(), 2);
        assertTurnCount(vietnamese.sessionId(), 2);
        verify(completion, times(4)).complete(anyList());
    }

    @Test
    void replayingChineseTurnDoesNotGenerateAgainOrRewindCurrentLanguage() throws Exception {
        var session = session(configure(create(), false, true, List.of(publicDocument.getId())));
        String question = "请用简体中文回答，如何安装eSIM？";
        String switchLanguage = "请用英语回答，如何查询流量？";
        expectRetrieval(question, switchLanguage);
        var original = message(session, question, "original-chinese");
        assertThat(original.result().message()).isEqualTo(CHINESE_ANSWER);
        assertThat(message(session, switchLanguage, "switch-english").result().message()).isEqualTo(ENGLISH_ANSWER);
        JsonNode beforeReplay = state(session.sessionId());

        assertThat(message(session, question, "original-chinese")).isEqualTo(original);

        assertThat(state(session.sessionId())).isEqualTo(beforeReplay);
        assertLanguage(session.sessionId(), "en", true);
        assertTurnCount(session.sessionId(), 2);
        assertThat(prompts).hasSize(2);
        verify(completion, times(2)).complete(anyList());
    }

    @Test
    void configuredChineseDialogSurvivesPublicationWithCoreDisabled() throws Exception {
        var configuration = chineseDialog(false);
        assertThat(configuration.bot().blueAiCoreEnabled()).isFalse();
        assertThat(studio.get(owner.getId(), configuration.bot().id()).dialogNodes())
                .filteredOn(node -> "travel-plan".equals(node.name())).singleElement()
                .satisfies(node -> assertThat(node.translations()).containsEntry("zh-CN",
                        new DialogReplyInput(CHINESE_PLAN, CHINESE_MISSING, CHINESE_IRRELEVANT)));
        var session = session(configuration);

        assertThat(message(session, "你好", "greeting").result().message()).isEqualTo(CHINESE_GREETING);
        assertThat(message(session, "套餐咨询", "collect-destination").result().message()).isEqualTo(CHINESE_MISSING);
        assertThat(message(session, "好的", "irrelevant").result().message()).isEqualTo(CHINESE_IRRELEVANT);
        var answer = message(session, "日本", "destination");

        assertThat(answer.result().message()).isEqualTo(CHINESE_PLAN.replace("{{destination}}", "Japan"));
        assertThat(answer.result().fallback()).isFalse();
        assertLanguage(session.sessionId(), "zh-CN", false);
        assertThat(state(session.sessionId()).path("dialogContext").path("variables").path("destination").asText())
                .isEqualTo("Japan");
        verifyNoInteractions(completion, clientFactory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"日本", "日本国"})
    void chineseIntentExamplesAndUnspacedSynonymsResolveCanonicalJapan(String synonym) throws Exception {
        var session = session(chineseDialog(true));
        String question = "请问去" + synonym + "旅游的套餐咨询有哪些？";
        assertThat(question).doesNotContain(" ");

        var answer = message(session, question, "chinese-plan");

        assertThat(answer.result().message()).isEqualTo(CHINESE_PLAN.replace("{{destination}}", "Japan"));
        assertThat(answer.result().fallback()).isFalse();
        JsonNode context = state(session.sessionId()).path("dialogContext");
        assertThat(context.path("lastIntent").asText()).isEqualTo("travel_plan");
        assertThat(context.path("variables").path("destination").asText()).isEqualTo("Japan");
        assertThat(context.hasNonNull("pendingEntity")).isFalse();
        assertLanguage(session.sessionId(), "zh-CN", false);
        verifyNoInteractions(completion, clientFactory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"套餐咨询日本eSIM", "eSIM日本出行套餐"})
    void chineseCountryAliasesWorkNextToLatinProductNames(String question) throws Exception {
        var session = session(chineseDialog(true));
        assertThat(message(session, question, "mixed-product-alias").result().message())
                .isEqualTo(CHINESE_PLAN.replace("{{destination}}", "Japan"));
        verifyNoInteractions(completion, clientFactory);
    }

    @Test
    void newChineseKnowledgeQuestionClearsPendingDialogButKeepsLanguage() throws Exception {
        var session = session(chineseDialog(true));
        assertThat(message(session, "套餐咨询", "pending-plan").result().message()).isEqualTo(CHINESE_MISSING);
        JsonNode pending = state(session.sessionId()).path("dialogContext");
        assertThat(pending.path("pendingIntent").asText()).isEqualTo("travel_plan");
        assertThat(pending.path("pendingEntity").asText()).isEqualTo("destination");
        verifyNoInteractions(completion, clientFactory);
        // Japan is also a recognized slot: a substantive new question must still leave the old task.
        String question = "我在日本无法连接网络，请问如何设置eSIM？";
        expectRetrieval(question);

        assertThat(message(session, question, "new-knowledge-question").result().message()).isEqualTo(CHINESE_ANSWER);

        assertPrompt(0, "Simplified Chinese (zh-CN)", question);
        JsonNode context = state(session.sessionId()).path("dialogContext");
        for (String field : List.of("pendingIntent", "pendingEntity", "lastIntent", "waitingNode")) {
            assertThat(context.hasNonNull(field)).as("cleared %s", field).isFalse();
        }
        assertThat(context.path("variables").size()).isZero();
        assertThat(context.path("suggestedVariables").size()).isZero();
        assertLanguage(session.sessionId(), "zh-CN", false);
        assertThat(prompts.getFirst()).contains(new LlmChatMessage("user", "套餐咨询"),
                new LlmChatMessage("assistant", CHINESE_MISSING));
        verify(completion, times(1)).complete(anyList());
    }

    @Test
    void previewDtoAcceptsZhCnReplyLanguage() throws Exception {
        var configuration = chineseDialog(false);
        preview(configuration, "zh-CN").andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedIntent").value("greeting"));
        verifyNoInteractions(completion, clientFactory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zh", "zh-TW", "ja", "fr"})
    void previewDtoRejectsUnsupportedReplyLanguage(String language) throws Exception {
        var configuration = chineseDialog(false);
        var response = preview(configuration, language).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).contains("context.replyLanguage");
        verifyNoInteractions(completion, clientFactory);
    }

    @Test
    void publishedApiChineseRequestUsesSharedRuntimeAndIdempotentReplay() throws Exception {
        var configuration = chineseDialog(true);
        var release = studio.publish(owner.getId(), configuration.bot().id());
        String key = (String) publishedApi.settings(owner.getId(), configuration.bot().id()).get("apiKey");
        assertThat(key).isNotBlank();
        String externalUser = "chinese-api-" + UUID.randomUUID();
        String conversation = "private-chat-" + UUID.randomUUID();
        String question = "请用简体中文回答，我的手机无法连接网络，如何设置eSIM？";
        expectRetrieval(question, "iPhone 15");
        JsonNode firstBody = apiBody(externalUser, conversation, "message-1", question);

        JsonNode first = apiChat(key, firstBody);
        JsonNode secondBody = apiBody(externalUser, conversation, "message-2", "iPhone 15");
        JsonNode second = apiChat(key, secondBody);
        JsonNode beforeReplay = state(first.path("sessionId").asText());
        JsonNode replay = apiChat(key, secondBody);

        assertThat(first.path("answer").asText()).isEqualTo(CHINESE_ANSWER);
        assertThat(second.path("answer").asText()).isEqualTo(CHINESE_ANSWER);
        assertThat(first.path("sessionId").asText()).isNotBlank();
        assertThat(second.path("sessionId")).isEqualTo(first.path("sessionId"));
        assertThat(replay).isEqualTo(second);
        for (JsonNode response : List.of(first, second, replay)) {
            assertThat(response.path("botId").asText()).isEqualTo(configuration.bot().id());
            assertThat(response.path("releaseId").asText()).isEqualTo(release.releaseId());
            assertThat(response.path("userId").asText()).isEqualTo(externalUser);
            assertThat(response.path("conversationId").asText()).isEqualTo(conversation);
            assertThat(response.path("fallback").asBoolean()).isFalse();
            assertThat(response.has("context")).isFalse();
            assertThat(response.has("trace")).isFalse();
            assertThat(response.has("sources")).isFalse();
            assertThat(response.toString()).doesNotContain(PRIVATE_EVIDENCE, privateDocument.getId());
        }
        assertThat(first.path("messageId").asText()).isEqualTo("message-1");
        assertThat(second.path("messageId").asText()).isEqualTo("message-2");
        String sessionId = first.path("sessionId").asText();
        assertThat(state(sessionId)).isEqualTo(beforeReplay);
        assertLanguage(sessionId, "zh-CN", true);
        assertTurnCount(sessionId, 2);
        assertPrompt(0, "Simplified Chinese (zh-CN)", question);
        assertPrompt(1, "Simplified Chinese (zh-CN)", "iPhone 15");
        assertThat(prompts.get(1)).contains(new LlmChatMessage("user", question),
                new LlmChatMessage("assistant", CHINESE_ANSWER));
        verify(completion, times(2)).complete(anyList());
    }

    @Test
    void chineseWithoutPublicKnowledgeReturnsLocalChineseFallbackWithNoProviders() throws Exception {
        var session = session(configure(create(), false, true, List.of()));

        var response = message(session, "请问如何安装eSIM并连接网络？", "no-public-evidence");

        assertThat(response.result().message()).isEqualTo("知识库中暂时没有足够的可靠信息来准确回答，请提供更多详细信息。");
        assertLanguage(session.sessionId(), "zh-CN", false);
        verifyNoInteractions(completion, clientFactory);
    }

    private RagDocument document(String filename, String marker) {
        var document = new RagDocument(owner, filename, UUID.randomUUID() + "-" + marker,
                "text/plain", 100, 500, 50);
        document.markReady(1);
        return documents.saveAndFlush(document);
    }

    private BotConfigurationResponse create() {
        return studio.create(owner.getId(), new CreateBotRequest("Chinese test " + UUID.randomUUID(),
                "Chinese runtime integration", "vi-VN", "Asia/Ho_Chi_Minh", true, BotCreationTemplate.RAG_DIALOG_DEMO));
    }

    private BotConfigurationResponse configure(BotConfigurationResponse configuration, boolean dialog,
                                                boolean core, List<String> publicIds) {
        var bot = configuration.bot();
        return studio.save(owner.getId(), bot.id(), new SaveConfigurationRequest(bot.draftRevision(), bot.name(),
                bot.description(), bot.locale(), bot.timezone(), core, bot.customInstructions(), configuration.intents(),
                configuration.entities(), configuration.dialogNodes(), configuration.customization(), dialog, publicIds));
    }

    private BotConfigurationResponse chineseDialog(boolean core) {
        var configuration = create();
        List<IntentInput> intents = List.of(
                new IntentInput("greeting", "Chinese greetings", List.of("你好", "您好")),
                new IntentInput("travel_plan", "Chinese plan questions", List.of("套餐咨询", "出行套餐")));
        List<EntityInput> entities = List.of(new EntityInput("destination", "Canonical travel country", List.of(
                new EntityValueInput("Japan", List.of("日本", "日本国")),
                new EntityValueInput("Vietnam", List.of("越南")))));
        List<DialogNodeInput> nodes = List.of(
                node("welcome", DialogNodeType.WELCOME, null, null, "Xin chào! Mình có thể giúp gì cho bạn?",
                        null, null, 0, new DialogReplyInput(CHINESE_GREETING, null, null)),
                node("greeting", DialogNodeType.ROUTE, "greeting", null, "Xin chào! Mình có thể giúp gì cho bạn?",
                        null, null, 10, new DialogReplyInput(CHINESE_GREETING, null, null)),
                node("travel-plan", DialogNodeType.ROUTE, "travel_plan", "destination",
                        "Gói tại {{destination}} có 10 GB trong 7 ngày.", "Bạn muốn đi nước nào?",
                        "Vui lòng cho biết quốc gia bạn muốn đến.", 20,
                        new DialogReplyInput(CHINESE_PLAN, CHINESE_MISSING, CHINESE_IRRELEVANT)),
                node("fallback", DialogNodeType.FALLBACK, null, null,
                        "Mình chưa hiểu yêu cầu này. Bạn thử diễn đạt theo cách khác nhé.", null, null, 100,
                        new DialogReplyInput("我还没有理解您的请求，请换一种方式描述。", null, null)));
        var bot = configuration.bot();
        return studio.save(owner.getId(), bot.id(), new SaveConfigurationRequest(bot.draftRevision(), bot.name(),
                bot.description(), bot.locale(), bot.timezone(), core, bot.customInstructions(), intents,
                entities, nodes, configuration.customization(), true, List.of(publicDocument.getId())));
    }

    private DialogNodeInput node(String name, DialogNodeType type, String intent, String requiredEntity,
                                 String response, String missingPrompt, String irrelevantResponse, int priority,
                                 DialogReplyInput chinese) {
        return new DialogNodeInput(name, type, null, intent, null, null, requiredEntity, response, missingPrompt,
                DialogNextStep.WAIT_FOR_REPLY, null, irrelevantResponse, priority, DialogContextReusePolicy.NONE,
                null, null, null, Map.of("zh-CN", chinese));
    }

    private CreateWidgetSessionResponse session(BotConfigurationResponse configuration) {
        studio.publish(owner.getId(), configuration.bot().id());
        return runtime.createSession(configuration.bot().publicKey(), CLIENT_ADDRESS);
    }

    private RuntimeMessageResponse message(CreateWidgetSessionResponse session, String text, String requestId) {
        return runtime.message(session.sessionId(), session.accessToken(),
                new RuntimeMessageRequest(requestId, text), CLIENT_ADDRESS);
    }

    private void expectRetrieval(String... questions) {
        for (String question : questions) {
            retrieval.expect(requestTo("http://chinese-rag.test/v1/retrieval/search"))
                    .andExpect(header("X-Worker-Api-Key", "test-worker-api-key-for-integration-tests"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.userId").value(owner.getId()))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.query").value(question))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.allowedDocumentIds[0]").value(publicDocument.getId()))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.allowedDocumentIds.length()").value(1))
                    // Deliberately return a higher-scoring unapproved document; the runtime must filter it out.
                    .andRespond(withSuccess("""
                            {"matches":[
                              {"documentId":"%s","chunkId":"public-1","content":"%s: Cài đặt > Thêm eSIM; 10 GB; hiệu lực 7 ngày.","score":0.9},
                              {"documentId":"%s","chunkId":"private-1","content":"%s","score":0.99}
                            ]}
                            """.formatted(publicDocument.getId(), PUBLIC_EVIDENCE,
                            privateDocument.getId(), PRIVATE_EVIDENCE), MediaType.APPLICATION_JSON));
        }
    }

    private void assertPrompt(int index, String language, String question) {
        List<LlmChatMessage> prompt = prompts.get(index);
        assertThat(prompt.getFirst().content()).contains("Reply language for this turn: " + language, PUBLIC_EVIDENCE);
        assertThat(prompt.getLast()).isEqualTo(new LlmChatMessage("user", question));
        assertThat(prompt).noneMatch(item -> item.content().contains(PRIVATE_EVIDENCE)
                || item.content().contains(privateDocument.getId()));
    }

    private JsonNode state(String sessionId) throws Exception {
        return mapper.readTree(jdbc.queryForObject("select state_json from studio_bot_sessions where id=?",
                String.class, sessionId));
    }

    private void assertLanguage(String sessionId, String language, boolean explicit) throws Exception {
        JsonNode context = state(sessionId).path("dialogContext");
        assertThat(context.path("replyLanguage").asText()).isEqualTo(language);
        assertThat(context.path("languageExplicit").asBoolean()).isEqualTo(explicit);
    }

    private void assertTurnCount(String sessionId, int count) {
        assertThat(jdbc.queryForObject("select turn_count from studio_bot_sessions where id=?", Integer.class, sessionId))
                .isEqualTo(count);
        assertThat(jdbc.queryForObject("select count(*) from studio_bot_turns where session_id=?", Integer.class, sessionId))
                .isEqualTo(count);
    }

    private ResultActions preview(BotConfigurationResponse configuration, String language) throws Exception {
        var context = new PreviewContext(null, null, null, Map.of(), null, Map.of(), null, language, true);
        return mvc.perform(post("/api/v1/studio/bots/" + configuration.bot().id() + "/preview")
                .with(jwt().jwt(token -> token.subject(owner.getId()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new PreviewRequest("你好", context))));
    }

    private JsonNode apiBody(String user, String conversation, String messageId, String message) {
        return mapper.createObjectNode().put("userId", user).put("conversationId", conversation)
                .put("channel", "telegram").put("messageId", messageId).put("message", message);
    }

    private JsonNode apiChat(String key, JsonNode body) throws Exception {
        var response = mvc.perform(post("/api/v1/ai/tasks/chat").header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
