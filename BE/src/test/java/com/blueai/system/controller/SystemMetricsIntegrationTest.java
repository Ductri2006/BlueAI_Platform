package com.blueai.system.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemMetricsIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ConversationMessageRepository messageRepository;
    @Autowired ConversationSummaryRepository summaryRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired RagDocumentRepository documentRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        summaryRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        documentRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void metricsComeFromDatabaseAndConversationCanBeDeleted() throws Exception {
        String token = register();
        String conversationResponse = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Delete me\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversationId = objectMapper.readTree(conversationResponse).get("id").asText();

        mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"A real persisted message\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/system/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(1))
                .andExpect(jsonPath("$.totalConversations").value(1))
                .andExpect(jsonPath("$.totalMessages").value(1))
                .andExpect(jsonPath("$.totalDocuments").value(0))
                .andExpect(jsonPath("$.aiRuntimeReady").value(false))
                .andExpect(jsonPath("$.aiRuntimeStatus").value("NOT_CONFIGURED"));

        mockMvc.perform(delete("/api/v1/conversations/{id}", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/conversations").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    private String register() throws Exception {
        userRepository.saveAndFlush(new User(
                "metrics@example.com",
                passwordEncoder.encode("StrongPassword123!"),
                "Metrics Admin",
                UserRole.ADMIN
        ));
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"metrics@example.com","password":"StrongPassword123!"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
