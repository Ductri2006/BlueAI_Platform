package com.blueai.rag.chat.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.studio.repository.BotDialogNodeRepository;
import com.blueai.studio.repository.BotEntityDefinitionRepository;
import com.blueai.studio.repository.BotEntityValueRepository;
import com.blueai.studio.repository.BotIntentRepository;
import com.blueai.studio.repository.BotReleaseRepository;
import com.blueai.studio.repository.BotRepository;
import com.blueai.studio.repository.BotSessionRepository;
import com.blueai.studio.repository.BotTurnRepository;
import com.blueai.system.service.GeminiUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RagChatConfiguredIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GeminiUsageService geminiUsageService;

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private RagDocumentRepository documentRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BotTurnRepository botTurnRepository;

    @Autowired
    private BotSessionRepository botSessionRepository;

    @Autowired
    private BotReleaseRepository botReleaseRepository;

    @Autowired
    private BotDialogNodeRepository botDialogNodeRepository;

    @Autowired
    private BotEntityValueRepository botEntityValueRepository;

    @Autowired
    private BotEntityDefinitionRepository botEntityDefinitionRepository;

    @Autowired
    private BotIntentRepository botIntentRepository;

    @Autowired
    private BotRepository botRepository;

    @MockitoBean
    private RagRestClientFactory restClientFactory;

    private MockRestServiceServer ragStub;

    @DynamicPropertySource
    static void ragProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rag.retrieval-base-url", () -> "http://rag-stub.test");
        registry.add("app.rag.llm-base-url", () -> "http://rag-stub.test");
        registry.add("app.rag.llm-api-key", () -> "configured-test-key");
        registry.add("app.rag.llm-model", () -> "configured-test-model");
        registry.add("app.rag.llm-max-attempts", () -> "3");
        registry.add("app.rag.llm-retry-initial-delay", () -> "1ms");
        registry.add("app.rag.llm-retry-max-delay", () -> "2ms");
        registry.add("app.rag.allow-shared-demo-knowledge", () -> "true");
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        ragStub = MockRestServiceServer.bindTo(restClientBuilder).ignoreExpectOrder(true).build();
        when(restClientFactory.create()).thenAnswer(invocation -> restClientBuilder.clone());
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    private void cleanDatabase() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        documentRepository.deleteAll();
        botTurnRepository.deleteAll();
        botSessionRepository.deleteAll();
        botReleaseRepository.deleteAll();
        botDialogNodeRepository.deleteAll();
        botEntityValueRepository.deleteAll();
        botEntityDefinitionRepository.deleteAll();
        botIntentRepository.deleteAll();
        botRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void activeDashboardRoutesIrrelevantReplyBeforeRagAndKeepsWaitingContext() throws Exception {
        String adminToken = registerAdminAndGetAccessToken("active-blueai-admin@example.com");
        JsonNode created = postJson(
                "/api/v1/studio/bots",
                adminToken,
                "{\"name\":\"BlueAI CSKH\",\"includeStarterTemplate\":true}",
                201
        );
        String botId = created.path("bot").path("id").asText();

        ObjectNode saveRequest = objectMapper.createObjectNode()
                .put("expectedRevision", created.path("bot").path("draftRevision").asLong())
                .put("name", "BlueAI CSKH")
                .put("description", "")
                .put("locale", "vi-VN")
                .put("timezone", "Asia/Ho_Chi_Minh")
                .put("blueAiCoreEnabled", true);
        saveRequest.set("intents", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("name", "hello")
                        .put("description", "Lời chào")
                        .set("examples", objectMapper.createArrayNode().add("xin chào").add("chào bạn"))));
        saveRequest.set("entities", objectMapper.createArrayNode());
        saveRequest.set("dialogNodes", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("name", "Welcome")
                        .put("type", "WELCOME")
                        .put("responseText", "Xin chào.")
                        .put("nextStep", "WAIT_FOR_REPLY")
                        .put("priority", 0))
                .add(objectMapper.createObjectNode()
                        .put("name", "Greeting")
                        .put("type", "ROUTE")
                        .put("matchIntent", "hello")
                        .put("responseText", "Mình đang chờ câu hỏi tiếp theo.")
                        .put("nextStep", "WAIT_FOR_REPLY")
                        .put("irrelevantResponseText", "Mình chỉ nhận câu hỏi liên quan đến yêu cầu hiện tại.")
                        .put("priority", 1))
                .add(objectMapper.createObjectNode()
                        .put("name", "Fallback")
                        .put("type", "FALLBACK")
                        .put("responseText", "Mình chưa hiểu yêu cầu này.")
                        .put("priority", 99)));
        putJson(
                "/api/v1/studio/bots/" + botId + "/configuration",
                adminToken,
                objectMapper.writeValueAsString(saveRequest),
                200
        );
        postJson("/api/v1/studio/bots/" + botId + "/publish", adminToken, "{}", 200);
        postJson("/api/v1/studio/bots/" + botId + "/activate", adminToken, "{}", 200);

        String accessToken = registerAndGetAccessToken();
        String conversationId = createConversation(accessToken);
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"xin chào\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.assistantMessage.content")
                        .value("Mình đang chờ câu hỏi tiếp theo."));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"tôi thích màu tím\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.assistantMessage.content")
                        .value("Mình chỉ nhận câu hỏi liên quan đến yêu cầu hiện tại."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources").isEmpty());

        assertThat(conversationRepository.findById(conversationId).orElseThrow().getDialogStateJson())
                .contains("Greeting");
        assertThat(messageRepository.count()).isEqualTo(4);
        ragStub.verify();
    }

    @Test
    void activeAdminConfigurationShapesUserDashboardPromptWithoutChangingUserRetrievalScope() throws Exception {
        String adminToken = registerAdminAndGetAccessToken("active-prompt-admin@example.com");
        JsonNode created = postJson(
                "/api/v1/studio/bots",
                adminToken,
                "{\"name\":\"BlueAI CSKH chính\",\"includeStarterTemplate\":true}",
                201
        );
        String botId = created.path("bot").path("id").asText();

        ObjectNode customization = objectMapper.createObjectNode()
                .put("identityRole", "CSKH_ACTIVE")
                .put("tonePreset", "warm")
                .put("answerLength", "short")
                .put("responseStructure", "bullets")
                .put("proactivity", "ask_when_needed")
                .put("citationPolicy", "always_when_available")
                .put("noSourcePolicy", "say_not_found")
                .put("fallbackMessage", "Chưa có dữ liệu phù hợp.");
        ObjectNode saveRequest = objectMapper.createObjectNode()
                .put("expectedRevision", created.path("bot").path("draftRevision").asLong())
                .put("name", "BlueAI CSKH chính")
                .put("locale", "vi-VN")
                .put("timezone", "Asia/Ho_Chi_Minh")
                .put("blueAiCoreEnabled", true)
                .put("customInstructions", "Luôn giới thiệu vai trò CSKH_ACTIVE khi cần.");
        saveRequest.set("intents", created.path("intents"));
        saveRequest.set("entities", created.path("entities"));
        saveRequest.set("dialogNodes", created.path("dialogNodes"));
        saveRequest.set("customization", customization);
        putJson(
                "/api/v1/studio/bots/" + botId + "/configuration",
                adminToken,
                objectMapper.writeValueAsString(saveRequest),
                200
        );
        postJson("/api/v1/studio/bots/" + botId + "/publish", adminToken, "{}", 200);
        postJson("/api/v1/studio/bots/" + botId + "/activate", adminToken, "{}", 200);

        String userToken = registerAndGetAccessToken();
        User requester = userRepository.findByEmail("configured-rag@example.com").orElseThrow();
        RagDocument document = new RagDocument(
                requester,
                "user-refund-policy.pdf",
                "test/user-refund-policy.pdf",
                "application/pdf",
                512,
                1_000,
                150
        );
        document.markReady(1);
        String documentId = documentRepository.saveAndFlush(document).getId();
        String conversationId = createConversation(userToken);

        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/retrieval/search"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.userId").value(requester.getId()))
                .andExpect(jsonPath("$.userId").value(org.hamcrest.Matchers.not(
                        userRepository.findByEmail("active-prompt-admin@example.com").orElseThrow().getId())))
                .andRespond(withSuccess("""
                        {"matches":[{"documentId":"%s","chunkId":"chunk-user-refund","content":"Khách hàng được hoàn tiền trong 7 ngày.","score":0.93}]}
                        """.formatted(documentId), MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.messages[0].content", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("CSKH_ACTIVE"),
                        org.hamcrest.Matchers.containsString("Luôn giới thiệu vai trò CSKH_ACTIVE khi cần."),
                        org.hamcrest.Matchers.containsString("BlueAI CSKH chính"))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Khách hàng được hoàn tiền trong 7 ngày. [S1]"}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Điều kiện hoàn tiền là gì?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.assistantMessage.content")
                        .value("Khách hàng được hoàn tiền trong 7 ngày. [S1]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].documentId")
                        .value(documentId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].sourceType")
                        .value("USER_DOCUMENT"));

        ragStub.verify();
    }

    @Test
    void activeEndpointAndRuntimeStayOnSelectedReleaseWhenNewReleaseIsPublished() throws Exception {
        String adminToken = registerAdminAndGetAccessToken("active-release-admin@example.com");
        JsonNode created = postJson(
                "/api/v1/studio/bots",
                adminToken,
                "{\"name\":\"BlueAI bản cũ\",\"includeStarterTemplate\":true}",
                201
        );
        String botId = created.path("bot").path("id").asText();
        ObjectNode firstSave = objectMapper.createObjectNode()
                .put("expectedRevision", created.path("bot").path("draftRevision").asLong())
                .put("name", "BlueAI bản cũ")
                .put("description", "Mô tả bản cũ")
                .put("locale", "vi-VN")
                .put("timezone", "Asia/Ho_Chi_Minh")
                .put("blueAiCoreEnabled", true)
                .put("customInstructions", "ACTIVE_OLD_RULE");
        firstSave.set("intents", created.path("intents"));
        firstSave.set("entities", created.path("entities"));
        firstSave.set("dialogNodes", created.path("dialogNodes"));
        putJson(
                "/api/v1/studio/bots/" + botId + "/configuration",
                adminToken,
                objectMapper.writeValueAsString(firstSave),
                200
        );
        String oldReleaseId = postJson(
                "/api/v1/studio/bots/" + botId + "/publish", adminToken, "{}", 200
        ).path("releaseId").asText();
        postJson("/api/v1/studio/bots/" + botId + "/activate", adminToken, "{}", 200);

        ObjectNode secondSave = firstSave.deepCopy();
        secondSave.put("expectedRevision", botRepository.findById(botId).orElseThrow().getDraftRevision());
        secondSave.put("name", "BlueAI bản mới chưa áp dụng");
        secondSave.put("description", "Mô tả bản mới chưa áp dụng");
        secondSave.put("customInstructions", "ACTIVE_NEW_RULE");
        putJson(
                "/api/v1/studio/bots/" + botId + "/configuration",
                adminToken,
                objectMapper.writeValueAsString(secondSave),
                200
        );
        String newReleaseId = postJson(
                "/api/v1/studio/bots/" + botId + "/publish", adminToken, "{}", 200
        ).path("releaseId").asText();
        assertThat(newReleaseId).isNotEqualTo(oldReleaseId);

        String activeBody = mockMvc.perform(get("/api/v1/studio/bots/active")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode active = objectMapper.readTree(activeBody);
        assertThat(active.path("bot").path("name").asText()).isEqualTo("BlueAI bản cũ");
        assertThat(active.path("bot").path("description").asText()).isEqualTo("Mô tả bản cũ");
        assertThat(active.path("bot").path("publishedReleaseId").asText()).isEqualTo(oldReleaseId);
        assertThat(active.path("bot").path("customInstructions").asText()).isEqualTo("ACTIVE_OLD_RULE");

        String userToken = registerAndGetAccessToken();
        User requester = userRepository.findByEmail("configured-rag@example.com").orElseThrow();
        RagDocument document = new RagDocument(
                requester,
                "active-release-policy.pdf",
                "test/active-release-policy.pdf",
                "application/pdf",
                512,
                1_000,
                150
        );
        document.markReady(1);
        String documentId = documentRepository.saveAndFlush(document).getId();
        String conversationId = createConversation(userToken);

        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/retrieval/search"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.userId").value(requester.getId()))
                .andRespond(withSuccess("""
                        {"matches":[{"documentId":"%s","chunkId":"active-old-chunk","content":"Nội dung release đang áp dụng.","score":0.97}]}
                        """.formatted(documentId), MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("BlueAI bản cũ"),
                        org.hamcrest.Matchers.containsString("ACTIVE_OLD_RULE"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ACTIVE_NEW_RULE")))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Nội dung release đang áp dụng. [S1]"}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Nội dung đang áp dụng là gì?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.assistantMessage.content")
                        .value("Nội dung release đang áp dụng. [S1]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].documentId")
                        .value(documentId));

        ragStub.verify();
    }

    @Test
    void askMapsRestClientFactoryIoFailureToBadGateway() throws Exception {
        String accessToken = registerAndGetAccessToken();
        String conversationId = createConversation(accessToken);
        when(restClientFactory.create()).thenThrow(new UncheckedIOException(new IOException("loopback selector failure")));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Kiểm tra lỗi kết nối RAG\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                        .value("RAG_RETRIEVAL_FAILED"));
    }

    @Test
    void askCallsConfiguredRetrieverAndLlmAndReturnsVerifiedSource() throws Exception {
        long previousUsageCount = geminiUsageService.report().lifetime().requests();
        String accessToken = registerAndGetAccessToken();
        User user = userRepository.findByEmail("configured-rag@example.com").orElseThrow();
        RagDocument document = new RagDocument(
                user,
                "refund-policy.pdf",
                "test/refund-policy.pdf",
                "application/pdf",
                512,
                1_000,
                150
        );
        document.markReady(1);
        String documentId = documentRepository.saveAndFlush(document).getId();
        String conversationId = createConversation(accessToken);

        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/retrieval/search"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.query").value("Điều kiện hoàn tiền là gì?"))
                .andRespond(withSuccess("""
                        {"matches":[{"documentId":"%s","chunkId":"chunk-refund-1","content":"Mã QR chưa quét đủ điều kiện xem xét hoàn tiền.","score":0.94}]}
                        """.formatted(documentId), MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer configured-test-key"))
                .andExpect(jsonPath("$.model").value("configured-test-model"))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("refund-policy.pdf")))
                .andRespond(withSuccess("""
                        {"model":"gemini-test","choices":[{"message":{"role":"assistant","content":"Mã QR chưa quét có thể được xem xét hoàn tiền. [S1]"}}],
                         "usage":{"prompt_tokens":140,"completion_tokens":32,"total_tokens":190}}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Điều kiện hoàn tiền là gì?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.assistantMessage.content")
                        .value("Mã QR chưa quét có thể được xem xét hoàn tiền. [S1]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].documentId").value(documentId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].filename").value("refund-policy.pdf"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].chunkId").value("chunk-refund-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].score").value(0.94));

        ragStub.verify();
        org.junit.jupiter.api.Assertions.assertEquals(2, messageRepository.count());
        assertThat(geminiUsageService.report().lifetime().requests()).isEqualTo(previousUsageCount + 1);
        assertThat(geminiUsageService.report().recent().getFirst().model()).isEqualTo("gemini-test");
        assertThat(geminiUsageService.report().recent().getFirst().promptTokens()).isEqualTo(140);
        assertThat(geminiUsageService.report().recent().getFirst().totalTokens()).isEqualTo(190);
    }

    @Test
    void askRetriesTransientLlmFailureAndReturnsGroundedAnswer() throws Exception {
        String accessToken = registerAndGetAccessToken();
        User user = userRepository.findByEmail("configured-rag@example.com").orElseThrow();
        RagDocument document = new RagDocument(
                user,
                "sales-playbook.pdf",
                "test/sales-playbook.pdf",
                "application/pdf",
                512,
                1_000,
                150
        );
        document.markReady(1);
        String documentId = documentRepository.saveAndFlush(document).getId();
        String conversationId = createConversation(accessToken);

        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/retrieval/search"))
                .andRespond(withSuccess("""
                        {"matches":[{"documentId":"%s","chunkId":"sales-1","content":"Ưu tiên báo giá trước thứ Sáu.","score":0.96}]}
                        """.formatted(documentId), MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Ưu tiên gửi báo giá trước thứ Sáu. [S1]"}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Khi nào cần gửi báo giá?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.assistantMessage.content")
                        .value("Ưu tiên gửi báo giá trước thứ Sáu. [S1]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].documentId")
                        .value(documentId));

        ragStub.verify();
        assertThat(messageRepository.count()).isEqualTo(2);
    }

    @Test
    void explicitlyAllowedDemoKnowledgeIsReturnedWithTrustMetadata() throws Exception {
        String accessToken = registerAndGetAccessToken();
        String conversationId = createConversation(accessToken);

        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/retrieval/search"))
                .andRespond(withSuccess("""
                        {"matches":[{"documentId":"POLICY-DEMO","chunkId":"REC-1","content":"Nội dung minh hoạ.","score":0.8,"filename":"Demo policy","sourceType":"SYNTHETIC_DEMO","approvalStatus":"PENDING_OWNER_REVIEW"}]}
                        """, MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("Demo policy")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Đây là dữ liệu mẫu, chưa được phê duyệt."}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Cho tôi ví dụ chính sách\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].sourceType").value("SYNTHETIC_DEMO"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources[0].approvalStatus").value("PENDING_OWNER_REVIEW"));

        ragStub.verify();
    }

    @Test
    void answerWithoutVerifiedSourceForbidsGeneralKnowledge() throws Exception {
        String accessToken = registerAndGetAccessToken();
        String conversationId = createConversation(accessToken);

        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/retrieval/search"))
                .andRespond(withSuccess("""
                        {"matches":[{"documentId":"UNKNOWN-DOC","chunkId":"chunk-1","content":"Chunk khong xac minh duoc trong MySQL.","score":0.7,"sourceType":"USER_DOCUMENT","approvalStatus":"READY"}]}
                        """, MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("No document sources were found"),
                        org.hamcrest.Matchers.containsString("do not answer it from your own general knowledge"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Chunk khong xac minh duoc")))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Kho tri thức nội bộ chưa có tài liệu về nội dung này."}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Giá gói eSIM Nhật Bản là bao nhiêu?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources").isEmpty());

        ragStub.verify();
    }

    private String createConversation(String accessToken) throws Exception {
        String response = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Configured RAG test\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private JsonNode postJson(String path, String accessToken, String body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private void putJson(String path, String accessToken, String body, int expectedStatus) throws Exception {
        mockMvc.perform(put(path)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    private String registerAndGetAccessToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "configured-rag@example.com",
                                  "password": "StrongPassword123!",
                                  "fullName": "Configured RAG User"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("accessToken").asText();
    }

    private String registerAdminAndGetAccessToken(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPassword123!",
                                  "fullName": "Active BlueAI Admin"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated());
        User admin = userRepository.findByEmail(email).orElseThrow();
        admin.changeRole(UserRole.ADMIN);
        userRepository.saveAndFlush(admin);
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPassword123!"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }

}
