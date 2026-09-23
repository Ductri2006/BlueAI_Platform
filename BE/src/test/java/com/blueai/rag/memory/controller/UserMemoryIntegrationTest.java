package com.blueai.rag.memory.controller;

import com.blueai.auth.BlueAiAuthApplication;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserMemoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMemoryRepository memoryRepository;

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        memoryRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void ownerCanUpsertListAndDeleteTheirMemory() throws Exception {
        String accessToken = registerAndGetAccessToken();

        mockMvc.perform(put("/api/v1/me/memories/{memoryKey}", "preferred-language")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Reply in Vietnamese\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("preferred-language"));

        mockMvc.perform(put("/api/v1/me/memories/{memoryKey}", "preferred-language")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Reply concisely in Vietnamese\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Reply concisely in Vietnamese"));

        mockMvc.perform(get("/api/v1/me/memories")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("preferred-language"));

        mockMvc.perform(delete("/api/v1/me/memories/{memoryKey}", "preferred-language")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/memories")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void conversationScopedMemoryRequiresAnOwnedConversationAndIsRemovedWithIt() throws Exception {
        String ownerToken = registerAndGetAccessToken("scoped-memory-owner@example.com");
        String otherToken = registerAndGetAccessToken("scoped-memory-other@example.com");
        String conversationId = createConversation(ownerToken, "Thông tin khách hàng");

        String messageBody = mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Khách hàng muốn nhận tư vấn bằng tiếng Việt.\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String sourceMessageId = objectMapper.readTree(messageBody).path("id").asText();

        String request = """
                {
                  "content":"Ưu tiên trả lời khách hàng này bằng tiếng Việt.",
                  "scope":"CONVERSATION",
                  "conversationId":"%s",
                  "sourceMessageId":"%s"
                }
                """.formatted(conversationId, sourceMessageId);
        mockMvc.perform(put("/api/v1/me/memories/{memoryKey}", "customer-language")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("CONVERSATION"))
                .andExpect(jsonPath("$.conversationId").value(conversationId))
                .andExpect(jsonPath("$.sourceMessageId").value(sourceMessageId));

        mockMvc.perform(put("/api/v1/me/memories/{memoryKey}", "other-user-memory")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content":"Không được phép lưu vào hội thoại của người khác.",
                                  "scope":"CONVERSATION",
                                  "conversationId":"%s"
                                }
                                """.formatted(conversationId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/me/memories/{memoryKey}", "invalid-user-scope")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content":"Không hợp lệ.",
                                  "scope":"USER",
                                  "conversationId":"%s"
                                }
                                """.formatted(conversationId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_SCOPE_CONVERSATION_UNEXPECTED"));

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}", conversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/memories")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
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
        return registerAndGetAccessToken("memory-owner@example.com");
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPassword123!",
                                  "fullName": "Memory Owner"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.get("accessToken").asText();
    }
}
