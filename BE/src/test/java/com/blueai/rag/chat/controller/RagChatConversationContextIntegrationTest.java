package com.blueai.rag.chat.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.blueai.rag.conversation.repository.ConversationLinkRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RagChatConversationContextIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationLinkRepository linkRepository;

    @Autowired
    private UserMemoryRepository memoryRepository;

    @Autowired
    private ConversationSummaryRepository summaryRepository;

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private RagRestClientFactory restClientFactory;

    private MockRestServiceServer ragStub;

    @DynamicPropertySource
    static void ragProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rag.retrieval-base-url", () -> "http://rag-context-stub.test");
        registry.add("app.rag.llm-base-url", () -> "http://rag-context-stub.test");
        registry.add("app.rag.llm-api-key", () -> "configured-test-key");
        registry.add("app.rag.llm-model", () -> "configured-test-model");
        registry.add("app.rag.allow-shared-demo-knowledge", () -> "true");
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        ragStub = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        when(restClientFactory.create()).thenAnswer(invocation -> builder.clone());
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void mixedRequestRetrievesDocumentsAndPersistsEvidenceForReload() throws Exception {
        String token = registerAndGetAccessToken("mixed-context@example.com");
        String conversationId = createConversation(token, "Hikari");
        mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Khách Hikari cần 80 eSIM Hàn Quốc.\"}"))
                .andExpect(status().isCreated());
        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/retrieval/search"))
                .andRespond(withSuccess("""
                        {"matches":[{"documentId":"test-only","chunkId":"test:1","content":"Bật Data Roaming.","score":0.8,"filename":"Fixture.csv","sourceType":"SYNTHETIC_DEMO","approvalStatus":"PENDING_OWNER_REVIEW"}]}
                        """, MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content", not(containsString("Turn mode: CONTEXT_ONLY"))))
                .andExpect(jsonPath("$.messages[0].content", containsString("Bật Data Roaming.")))
                .andExpect(jsonPath("$.messages[1].content", containsString("80 eSIM")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Hikari cần 80 eSIM. Hướng dẫn thử nghiệm: bật Data Roaming. [S1]"}}]}
                        """, MediaType.APPLICATION_JSON));
        mockMvc.perform(post("/api/v1/conversations/{id}/ask", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Nhắc lại số lượng từ context, rồi theo Knowledge Base hướng dẫn xử lý eSIM không có sóng.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[2].evidence.sources[0].excerpt").value("Bật Data Roaming."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[2].evidence.contextSources[0].type").value("CURRENT_CONVERSATION"));
        String otherToken = registerAndGetAccessToken("mixed-other@example.com");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        ragStub.verify();
    }

    @Test
    void missingPersonalContextDoesNotRetrieveUnrelatedKnowledge() throws Exception {
        String token = registerAndGetAccessToken("missing-context@example.com");
        String conversationId = createConversation(token, "Empty");
        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content", containsString("Turn mode: CONTEXT_ONLY")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Chưa ghi nhận mã cơ hội."}}]}
                        """, MediaType.APPLICATION_JSON));
        mockMvc.perform(post("/api/v1/conversations/{id}/ask", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Bạn còn nhớ mã cơ hội của tôi không?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources").isEmpty());
        ragStub.verify();
    }

    @Test
    void linkedConversationIsBoundedIntoThePromptAndReportedToTheClient() throws Exception {
        String accessToken = registerAndGetAccessToken();
        String sourceConversationId = createConversation(accessToken, "Báo giá khách hàng A");
        String targetConversationId = createConversation(accessToken, "Chuẩn bị trả lời khách hàng");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", sourceConversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Khách hàng A cần gói eSIM Nhật Bản bảy ngày.\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links", targetConversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(sourceConversationId)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v1/conversations/{conversationId}/context", targetConversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"LINKED_CONVERSATIONS\"}"))
                .andExpect(status().isOk());

        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/retrieval/search"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.query").value("Khách hàng A đang cần gì?"))
                .andRespond(withSuccess("{\"matches\":[]}", MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.messages[0].content", containsString("[LINKED_CONVERSATION] Báo giá khách hàng A:")))
                .andExpect(jsonPath("$.messages[0].content", containsString("Khách hàng A cần gói eSIM Nhật Bản bảy ngày.")))
                .andExpect(jsonPath("$.messages[0].content", containsString("untrusted historical data")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Kho tri thức chưa có tài liệu để trả lời chính sách này."}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", targetConversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Khách hàng A đang cần gì?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].type")
                        .value("LINKED_CONVERSATION"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].conversationId")
                        .value(sourceConversationId))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].title")
                        .value("Báo giá khách hàng A"));

        ragStub.verify();
    }

    @Test
    void currentConversationFactsRemainAvailableWithoutDocumentCitations() throws Exception {
        String accessToken = registerAndGetAccessToken("current-context@example.com");
        String conversationId = createConversation(accessToken, "Cơ hội Sao Mai");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Mã cơ hội BLUE-SALES-9274, khách Sao Mai cần 120 eSIM Nhật Bản trước 16:00 thứ Sáu."}
                                """))
                .andExpect(status().isCreated());

        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.messages[0].content", containsString(
                        "answer directly from conversation context")))
                .andExpect(jsonPath("$.messages[0].content", containsString(
                        "do not attach an [S#] label to a user-provided fact")))
                .andExpect(jsonPath("$.messages[0].content", containsString("Turn mode: CONTEXT_ONLY")))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content", containsString("BLUE-SALES-9274")))
                .andExpect(jsonPath("$.messages[2].content").value(
                        "Không nhắc lại dữ kiện trong câu hỏi: mã cơ hội, số lượng, thị trường và hạn báo giá là gì? Chỉ trả lời từ nội dung tôi đã cung cấp."))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"BLUE-SALES-9274; 120 eSIM; Nhật Bản; trước 16:00 thứ Sáu."}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", conversationId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"Không nhắc lại dữ kiện trong câu hỏi: mã cơ hội, số lượng, thị trường và hạn báo giá là gì? Chỉ trả lời từ nội dung tôi đã cung cấp."}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.assistantMessage.content")
                        .value("BLUE-SALES-9274; 120 eSIM; Nhật Bản; trước 16:00 thứ Sáu."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sources").isEmpty())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].type")
                        .value("CURRENT_CONVERSATION"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].conversationId")
                        .value(conversationId));

        ragStub.verify();
    }

    @Test
    void relevantHistoryUsesOnlyTheCurrentOwnersMatchingConversations() throws Exception {
        String ownerToken = registerAndGetAccessToken("relevant-owner@example.com");
        String otherToken = registerAndGetAccessToken("relevant-other@example.com");
        String sourceConversationId = createConversation(ownerToken, "Tư vấn khách Trần");
        String targetConversationId = createConversation(ownerToken, "Chuẩn bị phản hồi");
        String otherConversationId = createConversation(otherToken, "Hồ sơ riêng tư khách Trần");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", sourceConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Khách Trần cần gói eSIM Hàn Quốc cho năm người.\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", otherConversationId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Khách Trần cần GÓI BÍ MẬT của một tài khoản khác.\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/conversations/{conversationId}/context", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"RELEVANT_HISTORY\"}"))
                .andExpect(status().isOk());

        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/retrieval/search"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"matches\":[]}", MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content", containsString("[RELATED_HISTORY] Tư vấn khách Trần:")))
                .andExpect(jsonPath("$.messages[0].content", containsString("gói eSIM Hàn Quốc cho năm người")))
                .andExpect(jsonPath("$.messages[0].content", not(containsString("GÓI BÍ MẬT"))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Kho tri thức chưa có tài liệu để trả lời chính sách này."}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/ask", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Khách Trần đang cần gói nào?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].type")
                        .value("RELATED_HISTORY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].conversationId")
                        .value(sourceConversationId));

        ragStub.verify();
    }

    @Test
    void persistsAndReloadsConfirmedUserMemoryWithoutLeakingItAcrossOwners() throws Exception {
        String ownerToken = registerAndGetAccessToken("memory-context-owner@example.com");
        String ownerConversationId = createConversation(ownerToken, "Khách Sao Mai");

        mockMvc.perform(put("/api/v1/me/memories/{memoryKey}", "customer-crm")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Khách Sao Mai có mã CRM CM-4821 và ưu tiên liên hệ qua email.","scope":"USER","status":"CONFIRMED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.key")
                        .value("customer-crm"));

        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/retrieval/search"))
                .andRespond(withSuccess("{\"matches\":[]}", MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content", containsString("customer-crm")))
                .andExpect(jsonPath("$.messages[0].content", containsString("CM-4821")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Mã CRM đã lưu là CM-4821; khách ưu tiên liên hệ qua email."}}]}
                        """, MediaType.APPLICATION_JSON));
        ragStub.expect(once(), requestTo("http://rag-context-stub.test/v1/chat/completions"))
                .andExpect(jsonPath("$.messages[0].content", not(containsString("CM-4821"))))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Chưa ghi nhận mã CRM cho tài khoản này."}}]}
                        """, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/v1/conversations/{id}/ask", ownerConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Bạn còn nhớ mã CRM và cách liên hệ khách Sao Mai không?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.assistantMessage.content").value(
                        "Mã CRM đã lưu là CM-4821; khách ưu tiên liên hệ qua email."))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.contextSources[0].type").value("USER_MEMORY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.contextSources[0].memoryKey").value("customer-crm"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/conversations/{id}/messages", ownerConversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[1].evidence.contextSources[0].type").value("USER_MEMORY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[1].evidence.contextSources[0].memoryKey").value("customer-crm"));

        String otherToken = registerAndGetAccessToken("memory-context-other@example.com");
        String otherConversationId = createConversation(otherToken, "Khách hàng");

        mockMvc.perform(post("/api/v1/conversations/{id}/ask", otherConversationId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Bạn còn nhớ mã CRM của khách hàng không?\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$.contextSources").isEmpty());

        ragStub.verify();
    }

    private void cleanDatabase() {
        linkRepository.deleteAll();
        memoryRepository.deleteAll();
        summaryRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    private String createConversation(String accessToken, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("id").asText();
    }

    private String registerAndGetAccessToken() throws Exception {
        return registerAndGetAccessToken("rag-context@example.com");
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPassword123!",
                                  "fullName": "RAG Context User"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("accessToken").asText();
    }
}
