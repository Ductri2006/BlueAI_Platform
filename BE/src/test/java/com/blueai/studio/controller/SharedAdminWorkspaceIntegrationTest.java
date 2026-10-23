package com.blueai.studio.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.blueai.studio.repository.BotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Same shared-workspace and concurrency contract is repeated on disposable MySQL. */
@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharedAdminWorkspaceIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private RagDocumentRepository documents;
    @Autowired private BotRepository bots;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private RagRestClientFactory clients;
    private MockRestServiceServer provider;

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rag.retrieval-base-url", () -> "http://shared-admin-stub.test");
        registry.add("app.rag.llm-base-url", () -> "http://shared-admin-stub.test");
        registry.add("app.rag.llm-api-key", () -> "fixture-not-a-real-key");
        registry.add("app.rag.llm-model", () -> "fixture-model");
    }

    @BeforeEach
    void setUp() throws Exception {
        clean();
        RestClient.Builder builder = RestClient.builder();
        provider = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        when(clients.create()).thenAnswer(invocation -> builder.clone());
    }

    @AfterEach
    void clean() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            String url = connection.getMetaData().getURL();
            assertThat(url.startsWith("jdbc:h2:mem:") || url.matches(
                    "jdbc:mysql://[^/]+/blueai_shared_admin_test(?:\\?.*)?")).isTrue();
        }
        for (String table : List.of("studio_bot_api_sessions", "studio_bot_api_keys", "studio_bot_api_settings",
                "studio_bot_turns", "studio_bot_sessions", "studio_bot_releases",
                "studio_bot_dialog_nodes", "studio_bot_entity_synonyms", "studio_bot_entity_values",
                "studio_bot_entities", "studio_bot_intent_examples", "studio_bot_intents", "studio_bots",
                "rag_conversation_links", "rag_user_memories", "rag_conversation_summaries",
                "rag_conversation_messages", "rag_conversations", "rag_documents", "refresh_tokens", "app_users")) {
            jdbc.update("delete from " + table);
        }
    }

    @Test
    void adminsShareDocumentsButNeverAnotherUsersPrivateFiles() throws Exception {
        String a = login("kb-a@test.example", UserRole.ADMIN);
        String b = login("kb-b@test.example", UserRole.ADMIN);
        String user = login("private@test.example", UserRole.USER);
        RagDocument sharedA = document("kb-a@test.example", "100% plan.txt", true);
        RagDocument sharedB = document("kb-b@test.example", "100% second.txt", false);
        RagDocument privateFile = document("private@test.example", "100% PRIVATE.txt", true);
        for (String token : List.of(a, b)) {
            JsonNode list = call(get("/api/v1/documents"), token, null, 200);
            assertThat(list.size()).isEqualTo(2);
            assertThat(list.toString()).doesNotContain(privateFile.getId(), "storageKey");
            JsonNode search = call(get("/api/v1/documents/search").param("q", "100%")
                    .param("size", "1"), token, null, 200);
            assertThat(search.path("totalItems").asInt()).isEqualTo(2);
            assertThat(call(get("/api/v1/documents").param("status", "READY"), token, null, 200).size()).isEqualTo(1);
            call(get("/api/v1/documents/" + sharedA.getId()), token, null, 200);
            call(get("/api/v1/documents/" + privateFile.getId()), token, null, 404);
            call(delete("/api/v1/documents/" + privateFile.getId()), token, null, 404);
        }
        call(get("/api/v1/documents"), user, null, 403);
        call(get("/api/v1/studio/bots"), user, null, 403);
        call(delete("/api/v1/documents/" + sharedA.getId()), b, null, 204);
        call(get("/api/v1/documents/" + sharedA.getId()), a, null, 404);
        assertThat(call(get("/api/v1/documents"), a, null, 200).get(0).path("id").asText()).isEqualTo(sharedB.getId());
        assertThat(documents.findByIdIncludingDeleted(sharedA.getId()).orElseThrow().getOwnerId())
                .isEqualTo(users.findByEmail("kb-a@test.example").orElseThrow().getId());
    }

    @Test
    void sharedAdminPermissionsDoNotTakeOverAnotherUsersLegacyPrivateBot() throws Exception {
        String admin = login("legacy-admin@test.example", UserRole.ADMIN);
        login("legacy-private@test.example", UserRole.USER);
        var privateBot = bots.saveAndFlush(new com.blueai.studio.model.Bot(
                users.findByEmail("legacy-private@test.example").orElseThrow(), "Private legacy bot", null,
                "vi-VN", "Asia/Ho_Chi_Minh"));
        JsonNode editable = create(admin, "Enterprise bot");
        assertThat(call(get("/api/v1/studio/bots"), admin, null, 200).size()).isEqualTo(1);
        String path = "/api/v1/studio/bots/" + privateBot.getId();
        call(get(path), admin, null, 404);
        call(put(path + "/configuration"), admin, body(editable), 404);
        call(post(path + "/preview"), admin, Map.of("message", "hello"), 404);
        call(post(path + "/publish"), admin, Map.of(), 404);
        call(post(path + "/activate"), admin, Map.of(), 404);
        call(get("/api/v1/studio/bots"), null, null, 401);
    }

    @Test
    void savingAndPublishingAcrossAdminsRetainsOtherBotsAndRejectsStaleDrafts() throws Exception {
        String a = login("bots-a@test.example", UserRole.ADMIN);
        String b = login("bots-b@test.example", UserRole.ADMIN);
        JsonNode first = create(a, "Shared first");
        JsonNode second = create(b, "Shared second");
        String id = first.path("bot").path("id").asText();
        String otherId = second.path("bot").path("id").asText();
        ObjectNode edited = body(first).put("description", "Updated by admin B");
        call(put("/api/v1/studio/bots/" + id + "/configuration"), b, edited, 200);
        assertThat(call(get("/api/v1/studio/bots/" + id), a, null, 200).path("bot").path("description").asText())
                .isEqualTo("Updated by admin B");
        assertThat(call(put("/api/v1/studio/bots/" + id + "/configuration"), a, edited, 409).path("code").asText())
                .isEqualTo("REVISION_CONFLICT");
        call(post("/api/v1/studio/bots/" + id + "/publish"), b, Map.of(), 200);
        JsonNode unchanged = call(get("/api/v1/studio/bots/" + otherId), a, null, 200);
        assertThat(unchanged.path("bot").path("name").asText()).isEqualTo("Shared second");
        assertThat(unchanged.path("bot").path("draftRevision").asInt()).isEqualTo(1);
        assertThat(unchanged.path("bot").path("publicKey")).isEqualTo(second.path("bot").path("publicKey"));
        assertSameIntents(unchanged.path("intents"), second.path("intents"));
        assertThat(unchanged.path("dialogNodes")).isEqualTo(second.path("dialogNodes"));
        assertThat(call(get("/api/v1/studio/bots"), a, null, 200).size()).isEqualTo(2);
        assertThat(call(get("/api/v1/studio/bots"), b, null, 200).size()).isEqualTo(2);
        assertThat(bots.findById(id).orElseThrow().getOwner().getId())
                .isEqualTo(users.findByEmail("bots-a@test.example").orElseThrow().getId());
    }

    @Test
    void switchingTheGlobalActiveBotDoesNotDeleteReleasesOrDisableOtherWidgets() throws Exception {
        String a = login("active-a@test.example", UserRole.ADMIN);
        String b = login("active-b@test.example", UserRole.ADMIN);
        JsonNode first = create(a, "Active first");
        JsonNode second = create(b, "Active second");
        String firstId = first.path("bot").path("id").asText();
        String secondId = second.path("bot").path("id").asText();
        JsonNode releaseA = call(post("/api/v1/studio/bots/" + firstId + "/publish"), b, Map.of(), 200);
        JsonNode releaseB = call(post("/api/v1/studio/bots/" + secondId + "/publish"), a, Map.of(), 200);
        call(post("/api/v1/studio/bots/" + firstId + "/activate"), b, Map.of(), 200);
        call(post("/api/v1/studio/bots/" + secondId + "/activate"), a, Map.of(), 200);
        assertThat(bots.count()).isEqualTo(2);
        assertThat(bots.findByActiveTrueOrderByUpdatedAtDesc()).hasSize(1);
        for (String token : List.of(a, b)) {
            assertThat(call(get("/api/v1/studio/bots/active"), token, null, 200).path("bot").path("id").asText())
                    .isEqualTo(secondId);
            assertThat(call(get("/api/v1/studio/bots"), token, null, 200).size()).isEqualTo(2);
            call(get("/api/v1/studio/bots/" + firstId), token, null, 200);
        }
        for (JsonNode release : List.of(releaseA, releaseB)) {
            call(post("/api/v1/widget/" + release.path("publicKey").asText() + "/sessions"), null, Map.of(), 201);
        }
        assertThat(jdbc.queryForObject("select count(*) from studio_bot_releases", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentAdminSavesHaveOneWinnerWithoutLosingDialogConfiguration() throws Exception {
        String a = login("race-save-a@test.example", UserRole.ADMIN);
        String b = login("race-save-b@test.example", UserRole.ADMIN);
        JsonNode config = create(a, "Save race");
        String id = config.path("bot").path("id").asText();
        ObjectNode bodyA = body(config).put("description", "A wins");
        ObjectNode bodyB = body(config).put("description", "B wins");
        List<Integer> statuses = race(
                () -> raw(put("/api/v1/studio/bots/" + id + "/configuration"), a, bodyA),
                () -> raw(put("/api/v1/studio/bots/" + id + "/configuration"), b, bodyB));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        JsonNode persisted = call(get("/api/v1/studio/bots/" + id), b, null, 200);
        assertThat(persisted.path("bot").path("draftRevision").asInt()).isEqualTo(2);
        assertSameIntents(persisted.path("intents"), config.path("intents"));
        assertThat(persisted.path("dialogNodes")).isEqualTo(config.path("dialogNodes"));
    }

    @Test
    void concurrentAdminActivationsLeaveExactlyOneGlobalSelectionAndKeepBothBots() throws Exception {
        String a = login("race-active-a@test.example", UserRole.ADMIN);
        String b = login("race-active-b@test.example", UserRole.ADMIN);
        String idA = create(a, "Race A").path("bot").path("id").asText();
        String idB = create(b, "Race B").path("bot").path("id").asText();
        call(post("/api/v1/studio/bots/" + idA + "/publish"), a, Map.of(), 200);
        call(post("/api/v1/studio/bots/" + idB + "/publish"), b, Map.of(), 200);
        assertThat(race(() -> raw(post("/api/v1/studio/bots/" + idA + "/activate"), a, Map.of()),
                () -> raw(post("/api/v1/studio/bots/" + idB + "/activate"), b, Map.of())))
                .containsExactly(200, 200);
        assertThat(bots.findByActiveTrueOrderByUpdatedAtDesc()).hasSize(1);
        assertThat(bots.count()).isEqualTo(2);
    }

    @Test
    void widgetAllowlistAcceptsSharedReadyDocumentsButRejectsPrivatePendingAndDeletedFiles() throws Exception {
        String a = login("widget-a@test.example", UserRole.ADMIN);
        login("widget-b@test.example", UserRole.ADMIN);
        login("widget-private@test.example", UserRole.USER);
        RagDocument shared = document("widget-b@test.example", "Shared.txt", true);
        RagDocument pending = document("widget-b@test.example", "Pending.txt", false);
        RagDocument privateFile = document("widget-private@test.example", "Private.txt", true);
        JsonNode config = create(a, "Shared widget");
        String id = config.path("bot").path("id").asText();
        for (RagDocument invalid : List.of(pending, privateFile)) {
            ObjectNode update = body(config);
            update.putArray("widgetDocumentIds").add(invalid.getId());
            assertThat(call(put("/api/v1/studio/bots/" + id + "/configuration"), a, update, 400)
                    .path("code").asText()).isEqualTo("WIDGET_DOCUMENT_UNAVAILABLE");
        }
        ObjectNode update = body(config).put("dialogEnabled", false);
        update.putArray("widgetDocumentIds").add(shared.getId());
        JsonNode saved = call(put("/api/v1/studio/bots/" + id + "/configuration"), a, update, 200);
        JsonNode release = call(post("/api/v1/studio/bots/" + id + "/publish"), a, Map.of(), 200);
        JsonNode session = call(post("/api/v1/widget/" + release.path("publicKey").asText() + "/sessions"), null, Map.of(), 201);
        provider.expect(once(), requestTo("http://shared-admin-stub.test/v1/retrieval/search"))
                .andExpect(jsonPath("$.allowedDocumentIds[0]").value(shared.getId()))
                .andRespond(withSuccess(matches(shared, privateFile), MediaType.APPLICATION_JSON));
        provider.expect(once(), requestTo("http://shared-admin-stub.test/v1/chat/completions"))
                .andRespond(request -> {
                    jsonPath("$.messages[0].content", containsString("SHARED_APPROVED_FIXTURE")).match(request);
                    jsonPath("$.messages[0].content", not(containsString("PRIVATE_UNSELECTED_FIXTURE"))).match(request);
                    return withSuccess(completion(), MediaType.APPLICATION_JSON).createResponse(request);
                });
        call(post("/api/v1/runtime/sessions/" + session.path("sessionId").asText() + "/messages")
                .header("X-Widget-Token", session.path("accessToken").asText()), null,
                Map.of("requestId", "shared-widget-1", "message", "eSIM cho máy lock?"), 200);
        provider.verify();
        call(delete("/api/v1/documents/" + shared.getId()), a, null, 204);
        call(post("/api/v1/studio/bots/" + id + "/publish"), a, Map.of(), 400);
        assertThat(saved.path("bot").path("widgetDocumentIds").size()).isEqualTo(1);
    }

    @Test
    void newAdminCanAnswerFromSharedKnowledgeWithoutSharingPrivateConversations() throws Exception {
        String a = login("chat-a@test.example", UserRole.ADMIN);
        String b = login("chat-b@test.example", UserRole.ADMIN);
        login("chat-private@test.example", UserRole.USER);
        RagDocument shared = document("chat-a@test.example", "Shared FAQ.txt", true);
        RagDocument privateFile = document("chat-private@test.example", "Private FAQ.txt", true);
        String conversation = call(post("/api/v1/conversations"), b, Map.of("title", "Admin B private chat"), 201).path("id").asText();
        provider.expect(once(), requestTo("http://shared-admin-stub.test/v1/retrieval/search"))
                .andRespond(withSuccess(matches(shared, privateFile), MediaType.APPLICATION_JSON));
        provider.expect(once(), requestTo("http://shared-admin-stub.test/v1/chat/completions"))
                .andRespond(request -> {
                    jsonPath("$.messages[0].content", containsString("SHARED_APPROVED_FIXTURE")).match(request);
                    jsonPath("$.messages[0].content", not(containsString("PRIVATE_UNSELECTED_FIXTURE"))).match(request);
                    return withSuccess(completion(), MediaType.APPLICATION_JSON).createResponse(request);
                });
        JsonNode answer = call(post("/api/v1/conversations/" + conversation + "/ask"), b,
                Map.of("question", "Điều kiện tương thích eSIM là gì?"), 200);
        assertThat(answer.path("sources").size()).isEqualTo(1);
        assertThat(answer.path("sources").get(0).path("documentId").asText()).isEqualTo(shared.getId());
        call(get("/api/v1/conversations/" + conversation + "/messages"), a, null, 404);
        call(get("/api/v1/conversations/" + conversation + "/messages"), b, null, 200);
        provider.verify();
    }

    @Test
    void realAdminLoginsSharePublishedKeyButKeepPersonalConversationsPrivate() throws Exception {
        String a = login("keys-admin-a@test.example", UserRole.ADMIN);
        String b = login("keys-admin-b@test.example", UserRole.ADMIN);
        String regular = login("keys-regular@test.example", UserRole.USER);
        JsonNode configuration = create(a, "Admin shared key");
        String botId = configuration.path("bot").path("id").asText();
        String path = "/api/v1/studio/bots/" + botId;
        call(post(path + "/publish"), a, Map.of(), 200);
        JsonNode original = call(get(path + "/api-key"), a, null, 200);
        JsonNode shared = call(get(path + "/api-key"), b, null, 200);
        assertThat(shared.path("apiKey")).isEqualTo(original.path("apiKey"));
        call(put(path + "/api-key-settings"), b, Map.of("requestsPerMinute", 8), 200);
        assertThat(call(get(path + "/api-key"), a, null, 200).path("requestsPerMinute").asInt()).isEqualTo(8);
        call(post(path + "/publish"), b, Map.of(), 200);
        for (String token : List.of(a, b)) {
            JsonNode current = call(get(path + "/api-key"), token, null, 200);
            assertThat(current.path("apiKey")).isEqualTo(original.path("apiKey"));
            assertThat(current.path("publishedRequestsPerMinute").asInt()).isEqualTo(8);
        }
        call(get(path + "/api-key"), regular, null, 403);
        call(put(path + "/api-key-settings"), regular, Map.of("requestsPerMinute", 10), 403);
        call(get(path + "/api-key"), null, null, 401);
        assertThat(jdbc.queryForObject("select count(*) from studio_bot_api_keys where bot_id=?", Integer.class, botId)).isEqualTo(1);

        String ownA = call(post("/api/v1/conversations"), a, Map.of("title", "Admin A private"), 201).path("id").asText();
        String ownB = call(post("/api/v1/conversations"), b, Map.of("title", "Admin B private"), 201).path("id").asText();
        for (String token : List.of(a, b)) {
            String own = token.equals(a) ? ownA : ownB;
            String other = token.equals(a) ? ownB : ownA;
            JsonNode list = call(get("/api/v1/conversations"), token, null, 200);
            assertThat(list.toString()).contains(own).doesNotContain(other);
            call(get("/api/v1/conversations/" + own + "/messages"), token, null, 200);
            call(get("/api/v1/conversations/" + other), token, null, 404);
            call(get("/api/v1/conversations/" + other + "/messages"), token, null, 404);
            call(post("/api/v1/conversations/" + other + "/ask"), token, Map.of("question", "Read private chat"), 404);
            call(delete("/api/v1/conversations/" + other), token, null, 404);
        }
    }

    private String login(String email, UserRole role) throws Exception {
        users.saveAndFlush(new User(email, passwords.encode("FixturePassword123!"), "QA fixture", role));
        return call(post("/api/v1/auth/login"), null,
                Map.of("email", email, "password", "FixturePassword123!"), 200).path("accessToken").asText();
    }

    private RagDocument document(String email, String filename, boolean ready) {
        User owner = users.findByEmail(email).orElseThrow();
        RagDocument document = new RagDocument(owner, filename, owner.getId() + "/" + filename,
                "text/plain", 100, 1000, 150);
        if (ready) document.markReady(1);
        return documents.saveAndFlush(document);
    }

    private JsonNode create(String token, String name) throws Exception {
        return call(post("/api/v1/studio/bots"), token, Map.of("name", name, "includeStarterTemplate", true), 201);
    }

    private ObjectNode body(JsonNode config) {
        ObjectNode body = ((ObjectNode) config.path("bot")).deepCopy();
        body.put("expectedRevision", body.path("draftRevision").asLong());
        for (String field : List.of("intents", "entities", "dialogNodes", "customization")) body.set(field, config.path(field));
        return body;
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s %s: %s", request, expected, response.getContentAsString()).isEqualTo(expected);
        return response.getContentAsString().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.getContentAsString());
    }

    private int raw(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
    }

    private List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return a.call(); });
            var second = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return b.call(); });
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private String matches(RagDocument shared, RagDocument privateFile) throws Exception {
        return mapper.writeValueAsString(Map.of("matches", List.of(
                Map.of("documentId", shared.getId(), "chunkId", "shared:1", "content", "SHARED_APPROVED_FIXTURE", "score", 0.9),
                Map.of("documentId", privateFile.getId(), "chunkId", "private:1", "content", "PRIVATE_UNSELECTED_FIXTURE", "score", 0.95))));
    }

    private String completion() {
        return """
                {"choices":[{"message":{"role":"assistant","content":"Đây là câu trả lời fixture từ nguồn được phép. [S1]"}}]}
                """;
    }

    private void assertSameIntents(JsonNode actual, JsonNode expected) {
        assertThat(actual.size()).isEqualTo(expected.size());
        for (int index = 0; index < actual.size(); index++) {
            assertThat(actual.get(index).path("name")).isEqualTo(expected.get(index).path("name"));
            assertThat(actual.get(index).path("description")).isEqualTo(expected.get(index).path("description"));
            // Training examples are stored as a set; DB ordering is not part
            // of their meaning, but missing or extra examples are a failure.
            var examples = java.util.stream.StreamSupport.stream(actual.get(index).path("examples").spliterator(), false)
                    .map(JsonNode::asText).toList();
            var expectedExamples = java.util.stream.StreamSupport.stream(expected.get(index).path("examples").spliterator(), false)
                    .map(JsonNode::asText).toList();
            assertThat(examples).containsExactlyInAnyOrderElementsOf(expectedExamples);
        }
    }
}
