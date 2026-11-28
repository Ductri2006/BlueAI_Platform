package com.blueai.rag.chat.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.ConversationSummary;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.memory.model.MemoryScope;
import com.blueai.rag.memory.model.MemoryStatus;
import com.blueai.rag.memory.model.UserMemory;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real persistence/controller/provider-request gates; no paid provider calls. */
@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // Rollback fixtures; AFTER_COMMIT summaries have their own integration test.
class RagContextProductionGateIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ConversationRepository conversations;
    @Autowired private ConversationMessageRepository messages;
    @Autowired private ConversationSummaryRepository summaries;
    @Autowired private UserMemoryRepository memories;
    @Autowired private UserRepository users;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @MockitoBean private RagRestClientFactory clients;
    private MockRestServiceServer provider;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.rag.llm-base-url", () -> "http://production-gate-stub.test");
        registry.add("app.rag.llm-api-key", () -> "synthetic-test-only");
        registry.add("app.rag.llm-model", () -> "synthetic-test-model");
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        provider = MockRestServiceServer.bindTo(builder).build();
        when(clients.create()).thenAnswer(invocation -> builder.clone());
    }

    @Test
    void hundredPersistedMessagesAndOversizedSummaryProduceBoundedProviderHistory() throws Exception {
        String token = register("long-history@example.test");
        Conversation conversation = createConversation(token);
        Instant fixtureStart = Instant.now().minusSeconds(1000);
        for (int i = 0; i < 100; i++) {
            String content = (i == 99 ? "NEWEST-CORRECTION: 42 sales, budget 3 million. " : "OLD-TURN-" + i + " ")
                    + "synthetic filler ".repeat(180);
            var persisted = messages.saveAndFlush(new ConversationMessage(conversation, MessageRole.USER, content));
            // This tests context budgets, so fixture order must not depend on wall-clock precision.
            jdbc.update("UPDATE rag_conversation_messages SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(fixtureStart.plusMillis(i)), persisted.getId());
        }
        entityManager.clear();
        conversation = conversations.findById(conversation.getId()).orElseThrow();
        String oversizedSummary = "SAVED-SUMMARY: Aurora opportunity. " + "summary filler ".repeat(500);
        summaries.saveAndFlush(new ConversationSummary(conversation, oversizedSummary, 80));
        expectCompletion(body -> {
            JsonNode prompt = body.path("messages");
            assertThat(prompt.size()).isBetween(3, 22); // One system, at most 20 history entries, one question.
            int historyCharacters = 0;
            for (int i = 1; i < prompt.size() - 1; i++) {
                assertThat(prompt.get(i).path("role").asText()).isIn("user", "assistant");
                historyCharacters += prompt.get(i).path("content").asText().length();
            }
            assertThat(historyCharacters).isLessThanOrEqualTo(6_500);
            assertThat(prompt.get(prompt.size() - 2).path("content").asText()).startsWith("NEWEST-CORRECTION:");
            assertThat(body.toString()).doesNotContain("OLD-TURN-0 ");
            assertThat(prompt.get(0).path("content").asText())
                    .contains("Earlier conversation summary:\n" + oversizedSummary.substring(0, 2_997) + "...")
                    .doesNotContain(oversizedSummary);
        });
        ask(token, conversation.getId());
        String response = mvc.perform(get("/api/v1/conversations/{id}/messages", conversation.getId())
                        .param("limit", "100").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode page = json.readTree(response);
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.get(97).path("content").asText()).startsWith("NEWEST-CORRECTION:");
        provider.verify();
    }

    @Test
    void proposedReplacedExpiredAndOtherConversationMemoriesNeverEnterFreshPrompt() throws Exception {
        String token = register("memory-status@example.test");
        Conversation current = createConversation(token);
        Conversation other = createConversation(token);
        upsert(token, "qa-customer-proposed", Map.of("content", "EXCLUDE-PROPOSED", "status", "PROPOSED"));
        upsert(token, "qa-customer-old", Map.of("content", "EXCLUDE-REPLACED"));
        upsert(token, "qa-customer-confirmed", Map.of("content", "INCLUDE-CONFIRMED", "replacesMemoryKey", "qa-customer-old"));
        upsert(token, "qa-customer-other", Map.of("content", "EXCLUDE-OTHER-CONVERSATION", "scope", "CONVERSATION", "conversationId", other.getId()));
        memories.saveAndFlush(new UserMemory(users.findByEmail("memory-status@example.test").orElseThrow(), "qa-customer-expired", "EXCLUDE-EXPIRED",
                MemoryScope.USER, null, null, MemoryStatus.CONFIRMED, Instant.now().minusSeconds(60)));
        expectCompletion(body -> assertThat(body.path("messages").get(0).path("content").asText())
                .contains("INCLUDE-CONFIRMED").doesNotContain("EXCLUDE-"));
        ask(token, current.getId());
        provider.verify();
    }

    @Test
    void deletedMemoryIsAbsentFromAnIndependentConversationProviderRequest() throws Exception {
        String token = register("memory-delete@example.test");
        upsert(token, "qa-customer-deleted", Map.of("content", "EXCLUDE-DELETED-MEMORY"));
        mvc.perform(delete("/api/v1/me/memories/qa-customer-deleted")
                .header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        Conversation fresh = createConversation(token);
        expectCompletion(body -> assertThat(body.toString()).doesNotContain("EXCLUDE-DELETED-MEMORY", "qa-customer-deleted"));
        ask(token, fresh.getId());
        provider.verify();
    }

    @Test
    void deletingLinkedSourceRemovesFrozenFactsFromFreshTargetPrompt() throws Exception {
        String token = register("source-delete@example.test");
        Conversation source = createConversation(token);
        Conversation target = createConversation(token);
        messages.saveAndFlush(new ConversationMessage(source, MessageRole.USER, "EXCLUDE-DELETED-SOURCE: 77 sales."));
        mvc.perform(post("/api/v1/conversations/{id}/context-links", target.getId())
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sourceConversationId", source.getId())))).andExpect(status().isCreated());
        mvc.perform(put("/api/v1/conversations/{id}/context", target.getId())
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"LINKED_CONVERSATIONS\"}")).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/conversations/{id}", source.getId())
                .header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        expectCompletion(body -> assertThat(body.toString()).doesNotContain("EXCLUDE-DELETED-SOURCE"));
        ask(token, target.getId());
        provider.verify();
    }

    @Test
    void automaticHistoryKeepsNamedCustomerButExcludesUnrelatedAndOtherOwners() throws Exception {
        String token = register("automatic-owner@example.test");
        String otherToken = register("automatic-other@example.test");
        Conversation matching = createConversation(token);
        Conversation unrelated = createConversation(token);
        Conversation privateConversation = createConversation(otherToken);
        Conversation target = createConversation(token);
        messages.saveAndFlush(new ConversationMessage(matching, MessageRole.USER,
                "QATarvos has 37 sales, a monthly budget of 2.9 million VND and opportunity code TV-4712."));
        messages.saveAndFlush(new ConversationMessage(unrelated, MessageRole.USER,
                "Plant rosemary and lavender in spring. EXCLUDE-UNRELATED."));
        messages.saveAndFlush(new ConversationMessage(privateConversation, MessageRole.USER,
                "QATarvos PRIVATE-OTHER-OWNER: 999 sales, do not leak."));
        mvc.perform(put("/api/v1/conversations/{id}/context", target.getId())
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"RELEVANT_HISTORY\"}")).andExpect(status().isOk());
        expectCompletion(body -> assertThat(body.path("messages").get(0).path("content").asText())
                .contains("QATarvos has 37 sales", "TV-4712").doesNotContain("EXCLUDE-UNRELATED", "PRIVATE-OTHER-OWNER"));
        mvc.perform(post("/api/v1/conversations/{id}/ask", target.getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("question", "Bạn còn nhớ khách QATarvos có bao nhiêu nhân viên sales, "
                                + "ngân sách tháng bao nhiêu và mã cơ hội gì không? Chỉ nhắc lại dữ kiện trong lịch sử liên quan của tôi."))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].type").value("RELATED_HISTORY"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contextSources[0].conversationId").value(matching.getId()));
        provider.verify();
    }

    private void expectCompletion(Consumer<JsonNode> assertion) {
        provider.expect(requestTo("http://production-gate-stub.test/v1/chat/completions"))
                .andExpect(request -> assertion.accept(json.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Đây là phản hồi kiểm thử ngữ cảnh.\"}}]}", MediaType.APPLICATION_JSON));
    }

    private void ask(String token, String conversationId) throws Exception {
        mvc.perform(post("/api/v1/conversations/{id}/ask", conversationId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Bạn còn nhớ thông tin khách qa-customer của tôi không? Chỉ nhắc lại dữ kiện đã ghi nhận.\"}"))
                .andExpect(status().isOk());
    }

    private void upsert(String token, String key, Map<String, String> body) throws Exception {
        mvc.perform(put("/api/v1/me/memories/{key}", key).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk());
    }

    private Conversation createConversation(String token) throws Exception {
        String response = mvc.perform(post("/api/v1/conversations").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Synthetic context production gate\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return conversations.findById(json.readTree(response).path("id").asText()).orElseThrow();
    }

    private String register(String email) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "SyntheticPassword2026!", "fullName", "QA Gate"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("accessToken").asText();
    }
}
