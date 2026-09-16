package com.blueai.studio.controller;

import com.blueai.auth.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagChatService;
import com.blueai.studio.repository.BotDialogNodeRepository;
import com.blueai.studio.repository.BotEntityDefinitionRepository;
import com.blueai.studio.repository.BotEntityValueRepository;
import com.blueai.studio.repository.BotIntentRepository;
import com.blueai.studio.repository.BotReleaseRepository;
import com.blueai.studio.repository.BotRepository;
import com.blueai.studio.repository.BotSessionRepository;
import com.blueai.studio.repository.BotTurnRepository;
import com.blueai.system.service.SystemMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BotStudioIntegrationTest {

    @MockitoBean private SystemMetricsService systemMetricsService;
    @MockitoBean private RagChatService ragChatService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private BotTurnRepository turnRepository;
    @Autowired private BotSessionRepository sessionRepository;
    @Autowired private BotReleaseRepository releaseRepository;
    @Autowired private BotDialogNodeRepository dialogNodeRepository;
    @Autowired private BotEntityValueRepository entityValueRepository;
    @Autowired private BotEntityDefinitionRepository entityRepository;
    @Autowired private BotIntentRepository intentRepository;
    @Autowired private BotRepository botRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        turnRepository.deleteAll();
        sessionRepository.deleteAll();
        releaseRepository.deleteAll();
        dialogNodeRepository.deleteAll();
        entityValueRepository.deleteAll();
        entityRepository.deleteAll();
        intentRepository.deleteAll();
        botRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void ownerCanBuildPreviewPublishAndUseIdempotentWidgetSession() throws Exception {
        String ownerToken = createAndGetAccessToken("studio.owner@example.com", UserRole.ADMIN);
        String otherToken = createAndGetAccessToken("studio.other@example.com", UserRole.USER);

        JsonNode created = postJson(
                "/api/v1/studio/bots",
                ownerToken,
                """
                        {
                          "name": "Cửa hàng mẫu",
                          "description": "Bot demo giờ mở cửa",
                          "locale": "vi-VN",
                          "timezone": "Asia/Ho_Chi_Minh",
                          "includeStarterTemplate": true
                        }
                        """,
                201
        );
        String botId = created.path("bot").path("id").asText();
        assertThat(created.path("bot").path("blueAiCoreEnabled").asBoolean()).isTrue();
        assertThat(created.path("intents").size()).isEqualTo(2);
        assertThat(created.path("entities").get(0).path("values").size()).isEqualTo(3);

        mockMvc.perform(get("/api/v1/studio/bots/{botId}", botId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());

        JsonNode firstPreview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                """
                        {"message":"Mấy giờ cửa hàng đóng cửa?"}
                        """,
                200
        );
        assertThat(firstPreview.path("matchedIntent").asText()).isEqualTo("store_hours");
        assertThat(firstPreview.path("context").path("pendingEntity").asText()).isEqualTo("location");

        JsonNode secondPreview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("message", "Sài Gòn")
                        .set("context", firstPreview.path("context"))),
                200
        );
        assertThat(secondPreview.path("message").asText()).contains("HCM").contains("22:00");
        assertThat(secondPreview.path("recognizedEntities").path("location").asText()).isEqualTo("HCM");

        JsonNode published = postJson("/api/v1/studio/bots/" + botId + "/publish", ownerToken, "{}", 200);
        String publicKey = published.path("publicKey").asText();
        assertThat(publicKey).isNotBlank();

        JsonNode widgetSession = postPublicJson("/api/v1/widget/" + publicKey + "/sessions", "{}", 201);
        String sessionId = widgetSession.path("sessionId").asText();
        String widgetToken = widgetSession.path("accessToken").asText();
        assertThat(widgetSession.path("welcome").path("message").asText()).contains("Xin chào");
        assertThat(widgetSession.path("welcome").has("context")).isFalse();

        String messageBody = """
                {"requestId":"req-hours-1","message":"Mấy giờ cửa hàng đóng cửa?"}
                """;
        JsonNode widgetFirst = postWidgetMessage(sessionId, widgetToken, messageBody, 200);
        JsonNode widgetReplay = postWidgetMessage(sessionId, widgetToken, messageBody, 200);
        assertThat(widgetReplay).isEqualTo(widgetFirst);
        assertThat(turnRepository.count()).isEqualTo(1);

        postWidgetMessage(
                sessionId,
                widgetToken,
                """
                        {"requestId":"req-hours-1","message":"Nội dung khác"}
                        """,
                409
        );

        JsonNode widgetSecond = postWidgetMessage(
                sessionId,
                widgetToken,
                """
                        {"requestId":"req-location-2","message":"Sài Gòn"}
                        """,
                200
        );
        assertThat(widgetSecond.path("result").path("message").asText()).contains("22:00");
        assertThat(widgetSecond.path("result").has("matchedIntent")).isFalse();
        assertThat(widgetSecond.path("result").has("recognizedEntities")).isFalse();
        assertThat(widgetSecond.path("result").has("matchedNode")).isFalse();
        assertThat(widgetSecond.path("result").has("context")).isFalse();
        assertThat(turnRepository.count()).isEqualTo(2);
    }

    @Test
    void unpublishedBotCannotCreatePublicSessionAndStudioRequiresAuthentication() throws Exception {
        String ownerToken = createAndGetAccessToken("draft.owner@example.com", UserRole.ADMIN);
        JsonNode created = postJson(
                "/api/v1/studio/bots",
                ownerToken,
                """
                        {"name":"Bot chưa publish","includeStarterTemplate":true}
                        """,
                201
        );

        mockMvc.perform(get("/api/v1/studio/bots"))
                .andExpect(status().isUnauthorized());

        postPublicJson(
                "/api/v1/widget/" + created.path("bot").path("publicKey").asText() + "/sessions",
                "{}",
                404
        );
    }

    @Test
    void publicWidgetCorsAllowsAnEmbeddingOriginWithoutCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/runtime/sessions/demo/messages")
                        .header("Origin", "https://embed.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-widget-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://embed.example"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
    }

    @Test
    void dialogCanSkipInputJumpAndReplyToIrrelevantAnswers() throws Exception {
        String ownerToken = createAndGetAccessToken("dialog.flow@example.com", UserRole.ADMIN);
        JsonNode configuration = postJson(
                "/api/v1/studio/bots",
                ownerToken,
                """
                        {"name":"Bot dialog flow","includeStarterTemplate":true}
                        """,
                201
        );
        String botId = configuration.path("bot").path("id").asText();
        ArrayNode nodes = (ArrayNode) configuration.path("dialogNodes");

        for (JsonNode node : nodes) {
            if ("Greeting".equals(node.path("name").asText())) {
                ((ObjectNode) node).put("nextStep", "SKIP_USER_INPUT");
            }
        }
        nodes.add(objectMapper.createObjectNode()
                .put("name", "Greeting bridge")
                .put("type", "ROUTE")
                .put("parentNode", "Greeting")
                .put("matchIntent", "greeting")
                .put("responseText", "Mình sẽ chuyển bạn sang bước chọn chi nhánh.")
                .put("nextStep", "JUMP_TO")
                .put("jumpToNode", "Ask location")
                .put("priority", 11));

        ObjectNode saveRequest = objectMapper.createObjectNode()
                .put("expectedRevision", configuration.path("bot").path("draftRevision").asLong())
                .put("name", configuration.path("bot").path("name").asText())
                .put("description", configuration.path("bot").path("description").asText())
                .put("locale", configuration.path("bot").path("locale").asText())
                .put("timezone", configuration.path("bot").path("timezone").asText());
        saveRequest.set("intents", configuration.path("intents"));
        saveRequest.set("entities", configuration.path("entities"));
        saveRequest.set("dialogNodes", nodes);
        putJson(
                "/api/v1/studio/bots/" + botId + "/configuration",
                ownerToken,
                objectMapper.writeValueAsString(saveRequest),
                200
        );

        JsonNode first = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                "{\"message\":\"xin chào\"}",
                200
        );
        assertThat(first.path("message").asText())
                .contains("Chào bạn")
                .contains("chuyển bạn")
                .contains("chi nhánh nào");
        assertThat(first.path("context").path("pendingEntity").asText()).isEqualTo("location");
        assertThat(first.path("context").path("waitingNode").asText()).isEqualTo("Ask location");

        JsonNode irrelevant = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("message", "tôi thích màu tím")
                        .set("context", first.path("context"))),
                200
        );
        assertThat(irrelevant.path("message").asText()).contains("chưa nhận ra chi nhánh");
        assertThat(irrelevant.path("fallback").asBoolean()).isTrue();
        assertThat(irrelevant.path("context").path("pendingEntity").asText()).isEqualTo("location");

        JsonNode validReply = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("message", "Sài Gòn")
                        .set("context", irrelevant.path("context"))),
                200
        );
        assertThat(validReply.path("message").asText()).contains("HCM").contains("22:00");
    }

    @Test
    void customAssistantUsesSharedBlueAiCoreForUnmatchedQuestions() throws Exception {
        String ownerToken = createAndGetAccessToken("blueai.custom@example.com", UserRole.ADMIN);
        JsonNode configuration = postJson(
                "/api/v1/studio/bots",
                ownerToken,
                """
                        {"name":"CSKH BlueAI","includeStarterTemplate":true}
                        """,
                201
        );
        String botId = configuration.path("bot").path("id").asText();
        String customInstructions = "Trả lời ngắn gọn theo chính sách CSKH.";

        ObjectNode saveRequest = objectMapper.createObjectNode()
                .put("expectedRevision", configuration.path("bot").path("draftRevision").asLong())
                .put("name", "CSKH BlueAI")
                .put("description", configuration.path("bot").path("description").asText())
                .put("locale", configuration.path("bot").path("locale").asText())
                .put("timezone", configuration.path("bot").path("timezone").asText())
                .put("blueAiCoreEnabled", true)
                .put("customInstructions", customInstructions);
        saveRequest.set("intents", configuration.path("intents"));
        saveRequest.set("entities", configuration.path("entities"));
        saveRequest.set("dialogNodes", configuration.path("dialogNodes"));
        JsonNode saved = putJson(
                "/api/v1/studio/bots/" + botId + "/configuration",
                ownerToken,
                objectMapper.writeValueAsString(saveRequest),
                200
        );
        assertThat(saved.path("bot").path("customInstructions").asText()).isEqualTo(customInstructions);

        when(ragChatService.answerForCustomAssistant(
                anyString(),
                eq("CSKH BlueAI"),
                eq(customInstructions),
                eq("Chính sách đổi trả là gì?")
        )).thenReturn("BlueAI đã tra cứu chính sách đổi trả cho bạn.");

        JsonNode response = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                "{\"message\":\"Chính sách đổi trả là gì?\"}",
                200
        );
        assertThat(response.path("message").asText()).contains("BlueAI").contains("đổi trả");
        assertThat(response.path("matchedNode").asText()).isEqualTo("BlueAI Core");
        assertThat(response.path("fallback").asBoolean()).isFalse();
    }

    private String createAndGetAccessToken(String email, UserRole role) throws Exception {
        userRepository.saveAndFlush(new User(
                email,
                passwordEncoder.encode("StrongPassword123!"),
                "Studio Owner",
                role
        ));
        JsonNode response = postPublicJson(
                "/api/v1/auth/login",
                """
                        {
                          "email":"%s",
                          "password":"StrongPassword123!"
                        }
                        """.formatted(email),
                200
        );
        return response.path("accessToken").asText();
    }

    private JsonNode postJson(String path, String token, String body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return response.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response);
    }

    private JsonNode putJson(String path, String token, String body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(put(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return response.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response);
    }

    private JsonNode postPublicJson(String path, String body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return response.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response);
    }

    private JsonNode postWidgetMessage(String sessionId, String token, String body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post("/api/v1/runtime/sessions/{sessionId}/messages", sessionId)
                        .header("X-Widget-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return response.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(response);
    }
}
