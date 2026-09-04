package com.blueai.rag.chat.controller;

import com.blueai.auth.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
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

import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RagChatConfiguredIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @MockitoBean
    private RagRestClientFactory restClientFactory;

    private MockRestServiceServer ragStub;

    @DynamicPropertySource
    static void ragProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rag.retrieval-base-url", () -> "http://rag-stub.test");
        registry.add("app.rag.llm-base-url", () -> "http://rag-stub.test");
        registry.add("app.rag.llm-api-key", () -> "configured-test-key");
        registry.add("app.rag.llm-model", () -> "configured-test-model");
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
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void askCallsConfiguredRetrieverAndLlmAndReturnsVerifiedSource() throws Exception {
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
                        {"choices":[{"message":{"role":"assistant","content":"Mã QR chưa quét có thể được xem xét hoàn tiền. [S1]"}}]}
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

}
