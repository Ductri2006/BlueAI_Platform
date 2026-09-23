package com.blueai.rag.conversation.controller;

import com.blueai.auth.BlueAiAuthApplication;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
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
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationContextIntegrationTest {

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

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        linkRepository.deleteAll();
        memoryRepository.deleteAll();
        summaryRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void ownerCanConfigureFrozenLinksAndDeletingTheTargetRemovesTheLink() throws Exception {
        String ownerToken = registerAndGetAccessToken("context-owner@example.com");
        String otherToken = registerAndGetAccessToken("context-other@example.com");
        String sourceConversationId = createConversation(ownerToken, "Báo giá khách hàng A");
        String targetConversationId = createConversation(ownerToken, "Chốt phương án");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", sourceConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Khách hàng A cần gói eSIM Nhật Bản trong 7 ngày.\"}"))
                .andExpect(status().isCreated());

        String linkBody = mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(sourceConversationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceConversationId").value(sourceConversationId))
                .andExpect(jsonPath("$.sourceConversationTitle").value("Báo giá khách hàng A"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String linkId = objectMapper.readTree(linkBody).path("id").asText();

        mockMvc.perform(get("/api/v1/conversations/{conversationId}/context", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CURRENT_CONVERSATION"))
                .andExpect(jsonPath("$.links[0].id").value(linkId));

        mockMvc.perform(put("/api/v1/conversations/{conversationId}/context", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"LINKED_CONVERSATIONS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("LINKED_CONVERSATIONS"));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links/{linkId}/refresh", targetConversationId, linkId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(linkId));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links", targetConversationId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(sourceConversationId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        assertThat(linkRepository.count()).isZero();
    }

    @Test
    void rejectsSelfReferentialAndDuplicateLinks() throws Exception {
        String ownerToken = registerAndGetAccessToken("context-validation@example.com");
        String sourceConversationId = createConversation(ownerToken, "Nguồn");
        String targetConversationId = createConversation(ownerToken, "Đích");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links", sourceConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(sourceConversationId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONVERSATION_LINK_SELF_REFERENCE"));

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(sourceConversationId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(sourceConversationId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_LINK_EXISTS"));
    }

    @Test
    void deletingASourceConversationRemovesLinksThatCouldExposeStaleContext() throws Exception {
        String ownerToken = registerAndGetAccessToken("context-source-delete@example.com");
        String sourceConversationId = createConversation(ownerToken, "Hội thoại nguồn cần xoá");
        String targetConversationId = createConversation(ownerToken, "Hội thoại đích vẫn giữ lại");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/context-links", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(sourceConversationId)))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}", sourceConversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        assertThat(linkRepository.count()).isZero();
        mockMvc.perform(get("/api/v1/conversations/{conversationId}/context", targetConversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links").isEmpty());
    }

    @Test
    void contextOperationsRejectAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/unknown/context"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/conversations/unknown/context")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"RELEVANT_HISTORY\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/conversations/unknown/context-links")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sourceConversationId\":\"unknown\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/conversations/unknown/context-links/unknown/refresh"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/conversations/unknown/context-links/unknown"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void otherUsersCannotReadChangeLinkRefreshOrDeleteContext() throws Exception {
        String owner = registerAndGetAccessToken("context-security-owner@example.com");
        String outsider = registerAndGetAccessToken("context-security-outsider@example.com");
        String source = createConversation(owner, "Private source");
        String target = createConversation(owner, "Private target");
        String outsiderTarget = createConversation(outsider, "Outsider target");
        String response = mockMvc.perform(post("/api/v1/conversations/{id}/context-links", target)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(source)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String linkId = objectMapper.readTree(response).path("id").asText();

        mockMvc.perform(get("/api/v1/conversations/{id}/context", target)
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/conversations/{id}/context", target)
                        .header("Authorization", "Bearer " + outsider)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"RELEVANT_HISTORY\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/conversations/{id}/context-links", outsiderTarget)
                        .header("Authorization", "Bearer " + outsider)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(source)))
                .andExpect(status().isNotFound());
        for (String id : new String[]{target, outsiderTarget}) {
            mockMvc.perform(post("/api/v1/conversations/{id}/context-links/{link}/refresh", id, linkId)
                            .header("Authorization", "Bearer " + outsider))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete("/api/v1/conversations/{id}/context-links/{link}", id, linkId)
                            .header("Authorization", "Bearer " + outsider))
                    .andExpect(status().isNotFound());
        }
        assertThat(linkRepository.count()).isEqualTo(1);
    }

    @Test
    void disabledOwnerCannotUseContextWithAnExistingAccessToken() throws Exception {
        String email = "context-disabled@example.com";
        String token = registerAndGetAccessToken(email);
        String source = createConversation(token, "Source");
        String target = createConversation(token, "Target");
        String response = mockMvc.perform(post("/api/v1/conversations/{id}/context-links", target)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(source)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String linkId = objectMapper.readTree(response).path("id").asText();
        var user = userRepository.findByEmail(email).orElseThrow();
        user.changeStatus(UserStatus.DISABLED);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/v1/conversations/{id}/context", target)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_DISABLED"));
        mockMvc.perform(put("/api/v1/conversations/{id}/context", target)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"RELEVANT_HISTORY\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/conversations/{id}/context-links", target)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceConversationId\":\"%s\"}".formatted(source)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/conversations/{id}/context-links/{link}/refresh", target, linkId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/conversations/{id}/context-links/{link}", target, linkId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(linkRepository.count()).isEqualTo(1);
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

    private String registerAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPassword123!",
                                  "fullName": "Context Owner"
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
