package com.blueai.rag.conversation.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        conversationRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void ownerCanCreateListAndReadConversation() throws Exception {
        String ownerToken = registerAndGetAccessToken("owner@example.com");
        String otherUserToken = registerAndGetAccessToken("other@example.com");

        String response = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Product knowledge"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Product knowledge"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String conversationId = objectMapper.readTree(response).get("id").asText();

        mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(conversationId));

        mockMvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Product knowledge"));

        mockMvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void deleteLeavesOnlyTombstoneAndNoReadableConversationContent() throws Exception {
        String ownerToken = registerAndGetAccessToken("delete-owner@example.com");
        String conversationId = createConversation(ownerToken, "Sensitive context");

        mockMvc.perform(post("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"secret customer detail\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/conversations/{conversationId}", conversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/conversations")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/conversations/{conversationId}", conversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/conversations/{conversationId}/messages", conversationId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());

        assertThat(messageRepository.count()).isZero();
        assertThat(conversationRepository.findById(conversationId)).isEmpty();
        assertThat(conversationRepository.findByIdAndOwnerIncludingDeleted(conversationId, userId(ownerToken)))
                .isPresent()
                .get()
                .extracting("deletedAt")
                .isNotNull();
    }

    @Test
    void searchPaginatesAndKeepsOwnerIsolationAndLiteralLikeCharacters() throws Exception {
        String ownerToken = registerAndGetAccessToken("search-owner@example.com");
        String otherToken = registerAndGetAccessToken("search-other@example.com");
        createConversation(ownerToken, "100% policy");
        createConversation(ownerToken, "1000 policy");
        createConversation(otherToken, "100% policy");

        mockMvc.perform(get("/api/v1/conversations/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "100%")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].title").value("100% policy"));
    }

    @Test
    void searchReturnsAdjacentPagesAndFiltersByContextMode() throws Exception {
        String ownerToken = registerAndGetAccessToken("page-owner@example.com");
        String otherToken = registerAndGetAccessToken("page-other@example.com");
        createConversation(ownerToken, "Paging policy alpha");
        createConversation(ownerToken, "Paging policy beta");
        createConversation(otherToken, "Paging policy outsider");

        mockMvc.perform(get("/api/v1/conversations/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "Paging policy")
                        .param("contextMode", "CURRENT_CONVERSATION")
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true));

        mockMvc.perform(get("/api/v1/conversations/search")
                        .header("Authorization", "Bearer " + ownerToken)
                        .param("q", "Paging policy")
                        .param("contextMode", "RELEVANT_HISTORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void contextHistoryMethodIsBoundedToFiftyActiveConversations() throws Exception {
        String email = "history-owner@example.com";
        String ownerToken = registerAndGetAccessToken(email);
        for (int index = 0; index < 51; index++) {
            createConversation(ownerToken, "History " + index);
        }

        String ownerId = userRepository.findByEmail(email).orElseThrow().getId();
        assertThat(conversationRepository.findTop50ByOwner_IdOrderByUpdatedAtDesc(ownerId))
                .hasSize(50)
                .allMatch(conversation -> !conversation.isDeleted());
    }

    private String createConversation(String token, String title) throws Exception {
        String response = mockMvc.perform(post("/api/v1/conversations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"%s\"}".formatted(title)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String userId(String token) {
        return userRepository.findByEmail("delete-owner@example.com").orElseThrow().getId();
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "StrongPassword123!",
                                  "fullName": "Chat User"
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
