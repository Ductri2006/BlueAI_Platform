package com.blueai.studio.controller;

import com.blueai.BlueAiAuthApplication;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
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

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BotStudioIntegrationTest {

    @MockitoBean private SystemMetricsService systemMetricsService;
    @MockitoBean private RagChatService ragChatService;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
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
        String ownerToken = registerAndGetAccessToken("studio.owner@example.com");
        String otherToken = registerAndGetAccessToken("studio.other@example.com");

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
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOT_NOT_FOUND"));

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

        JsonNode directLocationPreview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                """
                        {"message":"Mấy giờ cửa hàng đóng cửa ở Sài Gòn?"}
                        """,
                200
        );
        assertThat(directLocationPreview.path("matchedNode").asText()).isEqualTo("HCM hours");
        assertThat(directLocationPreview.path("message").asText()).contains("HCM").contains("22:00");

        JsonNode hanoiPreview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview", ownerToken,
                "{\"message\":\"Hà Nội mở cửa lúc mấy giờ?\"}", 200
        );
        assertThat(hanoiPreview.path("matchedNode").asText()).isEqualTo("Hà Nội hours");
        JsonNode unknownLocationPreview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview", ownerToken,
                objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("message", "Trà Vinh mở cửa lúc mấy giừo?")
                        .set("context", hanoiPreview.path("context"))), 200
        );
        assertThat(unknownLocationPreview.path("matchedNode").asText()).isEqualTo("Ask location");
        assertThat(unknownLocationPreview.path("message").asText()).contains("chi nhánh nào");
        assertThat(unknownLocationPreview.path("context").path("variables").has("location")).isFalse();

        JsonNode switchedLocationPreview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview", ownerToken,
                objectMapper.writeValueAsString(objectMapper.createObjectNode()
                        .put("message", "Sài Gòn mở cửa lúc mấy giờ?")
                        .set("context", hanoiPreview.path("context"))), 200
        );
        assertThat(switchedLocationPreview.path("matchedNode").asText()).isEqualTo("HCM hours");
        assertThat(switchedLocationPreview.path("recognizedEntities").path("location").asText()).isEqualTo("HCM");

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

        JsonNode sameMessageWithNewRequestId = postWidgetMessage(
                sessionId,
                widgetToken,
                messageBody.replace("req-hours-1", "req-hours-retry"),
                200
        );
        assertThat(sameMessageWithNewRequestId.path("requestId").asText()).isEqualTo("req-hours-retry");
        assertThat(sameMessageWithNewRequestId.path("result")).isEqualTo(widgetFirst.path("result"));
        // An exact repeat with a fresh requestId reuses the recent response;
        // it records only that idempotency mapping and does not invoke AI.
        assertThat(turnRepository.count()).isEqualTo(2);

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
        assertThat(turnRepository.count()).isEqualTo(3);

        JsonNode widgetUnknownLocation = postWidgetMessage(
                sessionId, widgetToken,
                "{\"requestId\":\"req-unknown-location-3\",\"message\":\"Trà Vinh mở cửa lúc mấy giừo?\"}",
                200
        );
        assertThat(widgetUnknownLocation.path("result").path("message").asText())
                .contains("chi nhánh nào")
                .doesNotContain("HCM");
    }

    @Test
    void twentySyntheticWidgetVisitorsKeepIndependentLocationContext() throws Exception {
        String ownerToken = registerAndGetAccessToken("studio.synthetic-visitors@example.com");
        JsonNode created = postJson("/api/v1/studio/bots", ownerToken,
                "{\"name\":\"Synthetic visitor test\",\"includeStarterTemplate\":true}", 201);
        String botId = created.path("bot").path("id").asText();
        String publicKey = postJson("/api/v1/studio/bots/" + botId + "/publish", ownerToken, "{}", 200)
                .path("publicKey").asText();

        try (ExecutorService executor = Executors.newFixedThreadPool(5)) {
            List<Future<Boolean>> visitors = IntStream.range(0, 20).mapToObj(index -> executor.submit(() -> {
                JsonNode session = postPublicJson("/api/v1/widget/" + publicKey + "/sessions", "{}", 201);
                String sessionId = session.path("sessionId").asText();
                String token = session.path("accessToken").asText();
                String location = index % 2 == 0 ? "Hà Nội" : "Sài Gòn";
                String expectedHours = index % 2 == 0 ? "Hà Nội" : "HCM";
                JsonNode first = postWidgetMessage(sessionId, token,
                        objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("requestId", "visitor-" + index + "-1")
                                .put("message", location + " mở cửa lúc mấy giờ?")), 200);
                JsonNode second = postWidgetMessage(sessionId, token,
                        objectMapper.writeValueAsString(objectMapper.createObjectNode()
                                .put("requestId", "visitor-" + index + "-2")
                                .put("message", "Trà Vinh mở cửa lúc mấy giừo?")), 200);
                return first.path("result").path("message").asText().contains(expectedHours)
                        && second.path("result").path("message").asText().contains("chi nhánh nào")
                        && !second.path("result").path("message").asText().contains(expectedHours);
            })).toList();
            for (Future<Boolean> visitor : visitors) {
                assertThat(visitor.get(60, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(sessionRepository.count()).isEqualTo(20);
        assertThat(turnRepository.count()).isEqualTo(40);
    }

    @Test
    void previewSelectsLateIntentAndEntityValueFromLargeConfiguration() throws Exception {
        String ownerToken = registerAndGetAccessToken("studio.large-catalog@example.com");
        JsonNode configuration = postJson(
                "/api/v1/studio/bots", ownerToken,
                "{\"name\":\"Large catalog\",\"includeStarterTemplate\":true}", 201
        );
        String botId = configuration.path("bot").path("id").asText();
        ArrayNode intents = (ArrayNode) configuration.path("intents");
        ArrayNode entities = (ArrayNode) configuration.path("entities");
        ArrayNode nodes = (ArrayNode) configuration.path("dialogNodes");

        for (int index = 0; index < 30; index++) {
            ObjectNode intent = objectMapper.createObjectNode()
                    .put("name", "service_" + index)
                    .put("description", "Dịch vụ mẫu " + index);
            intent.putArray("examples")
                    .add("tra cuu dich vu mau " + index)
                    .add("hoi dich vu mau " + index);
            intents.add(intent);
        }
        for (int entityIndex = 0; entityIndex < 15; entityIndex++) {
            ObjectNode entity = objectMapper.createObjectNode()
                    .put("name", "zone_" + entityIndex)
                    .put("description", "Khu vực " + entityIndex);
            ArrayNode values = entity.putArray("values");
            for (int valueIndex = 0; valueIndex < 12; valueIndex++) {
                ObjectNode value = objectMapper.createObjectNode()
                        .put("value", "Region " + entityIndex + "-" + valueIndex);
                value.putArray("synonyms").add("z" + entityIndex + "v" + valueIndex);
                values.add(value);
            }
            entities.add(entity);
        }
        nodes.add(objectMapper.createObjectNode()
                .put("name", "Late catalog route")
                .put("type", "ROUTE")
                .put("matchIntent", "service_29")
                .put("matchEntityName", "zone_14")
                .put("matchEntityValue", "Region 14-11")
                .put("responseText", "Đã chọn {{zone_14}}")
                .put("priority", 500));

        ObjectNode saveRequest = objectMapper.createObjectNode()
                .put("expectedRevision", configuration.path("bot").path("draftRevision").asLong())
                .put("name", configuration.path("bot").path("name").asText())
                .put("description", configuration.path("bot").path("description").asText())
                .put("locale", configuration.path("bot").path("locale").asText())
                .put("timezone", configuration.path("bot").path("timezone").asText());
        saveRequest.set("intents", intents);
        saveRequest.set("entities", entities);
        saveRequest.set("dialogNodes", nodes);
        putJson("/api/v1/studio/bots/" + botId + "/configuration", ownerToken,
                objectMapper.writeValueAsString(saveRequest), 200);

        JsonNode preview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview", ownerToken,
                "{\"message\":\"tra cuu dich vu mau 29 z14v11\"}", 200
        );
        assertThat(preview.path("matchedIntent").asText()).isEqualTo("service_29");
        assertThat(preview.path("recognizedEntities").path("zone_14").asText()).isEqualTo("Region 14-11");
        assertThat(preview.path("matchedNode").asText()).isEqualTo("Late catalog route");
        assertThat(preview.path("message").asText()).isEqualTo("Đã chọn Region 14-11");
    }

    @Test
    void unpublishedBotCannotCreatePublicSessionAndStudioRequiresAuthentication() throws Exception {
        String ownerToken = registerAndGetAccessToken("draft.owner@example.com");
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
    void publicWidgetCorsUsesWidgetOriginPolicyAndExposesRetryAfter() throws Exception {
        mockMvc.perform(options("/api/v1/runtime/sessions/{sessionId}/messages", "widget-session")
                        .header("Origin", "https://widget-user.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-widget-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://widget-user.example"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("x-widget-token")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void actuatorMetricsRequireAdminWhileHealthRemainsPublic() throws Exception {
        String userToken = registerUserAndGetAccessToken("metrics.user@example.com");
        String adminToken = registerAndGetAccessToken("metrics.admin@example.com");

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void dialogCanSkipInputJumpAndReplyToIrrelevantAnswers() throws Exception {
        String ownerToken = registerAndGetAccessToken("dialog.flow@example.com");
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
    void blankResponseJumpRouteSavesAndReachesDestinationWithoutExtraMessage() throws Exception {
        String ownerToken = registerAndGetAccessToken("dialog.jump-only@example.com");
        JsonNode configuration = postJson(
                "/api/v1/studio/bots",
                ownerToken,
                "{\"name\":\"Bot jump-only flow\",\"includeStarterTemplate\":true}",
                201
        );
        String botId = configuration.path("bot").path("id").asText();
        ArrayNode nodes = (ArrayNode) configuration.path("dialogNodes");
        nodes.add(objectMapper.createObjectNode()
                .put("name", "Jump without response")
                .put("type", "ROUTE")
                .put("matchIntent", "store_hours")
                .put("responseText", "")
                .put("nextStep", "JUMP_TO")
                .put("jumpToNode", "Jump destination")
                .put("priority", 15));
        nodes.add(objectMapper.createObjectNode()
                .put("name", "Jump destination")
                .put("type", "ROUTE")
                .put("matchIntent", "store_hours")
                .put("responseText", "Đây là nội dung ở node đích.")
                .put("nextStep", "WAIT_FOR_REPLY")
                .put("priority", 16));

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

        JsonNode preview = postJson(
                "/api/v1/studio/bots/" + botId + "/preview",
                ownerToken,
                "{\"message\":\"Mấy giờ cửa hàng đóng cửa?\"}",
                200
        );
        assertThat(preview.path("message").asText()).isEqualTo("Đây là nội dung ở node đích.");
        assertThat(preview.path("matchedNode").asText()).isEqualTo("Jump destination");
    }

    @Test
    void customAssistantUsesSharedBlueAiCoreForUnmatchedQuestions() throws Exception {
        String ownerToken = registerAndGetAccessToken("blueai.custom@example.com");
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
        saveRequest.set("customization", objectMapper.createObjectNode()
                .put("identityRole", "Trợ lý CSKH thân thiện")
                .put("tonePreset", "friendly")
                .put("answerLength", "short")
                .put("responseStructure", "steps")
                .put("proactivity", "proactive")
                .put("citationPolicy", "always_when_available")
                .put("noSourcePolicy", "say_not_found"));
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

        when(ragChatService.previewWithSnapshot(
                anyString(),
                argThat(snapshot -> snapshot != null && "CSKH BlueAI".equals(snapshot.name())
                        && customInstructions.equals(snapshot.customInstructions())
                        && "friendly".equals(snapshot.customization().tonePreset())
                        && "short".equals(snapshot.customization().answerLength())
                        && "steps".equals(snapshot.customization().responseStructure())
                        && "proactive".equals(snapshot.customization().proactivity())),
                argThat(request -> "Chính sách đổi trả là gì?".equals(request.message()))
        )).thenReturn(new com.blueai.studio.dto.BotStudioDtos.RuntimeResponse(
                "BlueAI đã tra cứu chính sách đổi trả cho bạn.", null, java.util.Map.of(), "BlueAI Core",
                new com.blueai.studio.dto.BotStudioDtos.PreviewContext(null, null, null, java.util.Map.of()), false));

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

    @Test
    void studioManagementRequiresAdminAndAdminCanActivatePublishedConfiguration() throws Exception {
        String userToken = registerUserAndGetAccessToken("studio.user@example.com");
        mockMvc.perform(get("/api/v1/studio/bots")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        String adminToken = registerAndGetAccessToken("studio.admin@example.com");
        JsonNode created = postJson(
                "/api/v1/studio/bots", adminToken,
                "{\"name\":\"Cấu hình BlueAI chính\",\"includeStarterTemplate\":true}", 201);
        String botId = created.path("bot").path("id").asText();
        postJson("/api/v1/studio/bots/" + botId + "/publish", adminToken, "{}", 200);
        JsonNode active = postJson("/api/v1/studio/bots/" + botId + "/activate", adminToken, "{}", 200);
        assertThat(active.path("bot").path("active").asBoolean()).isTrue();
        assertThat(active.path("customization").path("identityRole").asText()).isEqualTo("Trợ lý BlueAI");
    }

    private String registerAndGetAccessToken(String email) throws Exception {
        postPublicJson(
                "/api/v1/auth/register",
                """
                        {
                          "email":"%s",
                          "password":"StrongPassword123!",
                          "fullName":"Studio Owner"
                        }
                """.formatted(email),
                201
        );
        User user = userRepository.findByEmail(email).orElseThrow();
        user.changeRole(UserRole.ADMIN);
        userRepository.saveAndFlush(user);
        JsonNode response = postPublicJson(
                "/api/v1/auth/login",
                "{\"email\":\"%s\",\"password\":\"StrongPassword123!\"}".formatted(email),
                200
        );
        return response.path("accessToken").asText();
    }

    private String registerUserAndGetAccessToken(String email) throws Exception {
        JsonNode response = postPublicJson(
                "/api/v1/auth/register",
                """
                        {"email":"%s","password":"StrongPassword123!","fullName":"Studio User"}
                        """.formatted(email),
                201
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
