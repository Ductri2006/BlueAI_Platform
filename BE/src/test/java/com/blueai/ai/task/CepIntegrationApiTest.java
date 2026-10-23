package com.blueai.ai.task;

import com.blueai.BlueAiAuthApplication;
import com.blueai.ai.task.provider.ConfiguredAiProviders;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagChatService;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CepIntegrationApiTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:cep-integration;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RagDocumentRepository documents;
    @MockitoBean ConfiguredAiProviders providers;
    @MockitoBean RagChatService ragChat;
    private User admin;

    @BeforeEach void fixture() {
        admin = users.save(new User(UUID.randomUUID()+"@cep.test", "unused", "CEP admin", UserRole.ADMIN));
        reset(providers, ragChat);
        when(providers.complete(anyString(), anyString(), anyList(), any()))
                .thenReturn("{\"label\":\"sales\",\"abstain\":false}");
    }

    @Test void contextSurvivesReplayPollAndExpirationWithoutBecomingModelInput() throws Exception {
        JsonNode client = client("Company-A", List.of("CLASSIFY"), List.of(), List.of());
        ObjectNode body = classify();
        String task = task(client, "same", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.externalContext.userId").value("9007199254740993"))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(task).path("taskId").asText();
        task(client, "same", body).andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(id))
                .andExpect(jsonPath("$.externalContext.messageId").value("msg-1"));
        mvc.perform(get("/api/v1/ai/tasks/"+id).header("Authorization", bearer(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.externalContext.companyId").value("Company-A"));
        verify(providers, times(1)).complete(argThat(s -> !s.contains("Company-A")), eq("hello"), eq(List.of()), any());
        jdbc.update("update ai_task_ledger set status='PROCESSING',response_json=null,completed_at=null,expires_at=? where id=?",
                Timestamp.from(Instant.now().minusSeconds(10)), id);
        mvc.perform(get("/api/v1/ai/tasks/"+id).header("Authorization", bearer(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("AI_TASK_EXPIRED"))
                .andExpect(jsonPath("$.externalContext.messageId").value("msg-1"));
        verify(providers, times(1)).complete(anyString(), anyString(), anyList(), any());
    }

    @Test void bindingRejectsMissingForgedAndCaseChangedCompanyBeforeClaimOrProvider() throws Exception {
        JsonNode client = client("Company-A", List.of("CLASSIFY"), List.of(), List.of());
        ObjectNode body = classify();
        body.remove("externalContext");
        task(client, "missing", body).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_CONTEXT_REQUIRED"));
        for (String company : List.of("Company-B", "company-a")) {
            body.set("externalContext", context().put("companyId", company));
            task(client, company, body).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("EXTERNAL_COMPANY_MISMATCH"));
        }
        body.set("externalContext", context().put("system", "forged"));
        task(client, "system", body).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from ai_task_ledger where client_id=?", Integer.class, client.path("id").asText())).isZero();
        verifyNoInteractions(providers);
    }

    @Test void idsAreStrictAndChangingCustomerConversationOrMessageConflictsOnReplay() throws Exception {
        JsonNode client = client("Company-A", List.of("CLASSIFY"), List.of(), List.of());
        task(client, "original", classify()).andExpect(status().isOk());
        for (String field : List.of("userId", "conversationId", "messageId", "channelId", "channel")) {
            ObjectNode changed = classify(); ((ObjectNode) changed.path("externalContext")).put(field, "different");
            task(client, "original", changed).andExpect(status().isConflict());
        }
        for (String field : List.of("userId", "messageId")) {
            ObjectNode invalid = classify(); ((ObjectNode) invalid.path("externalContext")).put(field, 123);
            task(client, "numeric-"+field, invalid).andExpect(status().isBadRequest());
        }
        ObjectNode invalid = classify(); ((ObjectNode) invalid.path("externalContext")).put("ownerId", "forged");
        task(client, "unknown", invalid).andExpect(status().isBadRequest());
        invalid.set("externalContext", context().put("userId", " leading"));
        task(client, "spaces", invalid).andExpect(status().isBadRequest());
        verify(providers, times(1)).complete(anyString(), anyString(), anyList(), any());
    }

    @Test void legacyClientStillWorksButCannotAssertAnExternalIdentity() throws Exception {
        JsonNode legacy = client(null, List.of("CLASSIFY"), List.of(), List.of());
        ObjectNode body = classify(); body.remove("externalContext");
        task(legacy, "legacy", body).andExpect(status().isOk()).andExpect(jsonPath("$.externalContext").doesNotExist());
        task(legacy, "forged", classify()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EXTERNAL_BINDING_REQUIRED"));
    }

    @Test void inFlightDuplicateKeepsPollLocationAndDurableContext() throws Exception {
        JsonNode client = client("Company-A", List.of("CLASSIFY"), List.of(), List.of());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(providers.complete(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            entered.countDown(); assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
            return "{\"label\":\"sales\",\"abstain\":false}";
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> task(client, "parallel", classify()).andReturn());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var response = task(client, "parallel", classify()).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.externalContext.conversationId").value("conv-1")).andReturn().getResponse();
            String location = response.getHeader("Location"); assertThat(location).startsWith("/api/v1/ai/tasks/");
            mvc.perform(get(location).header("Authorization", bearer(client))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PROCESSING"))
                    .andExpect(jsonPath("$.externalContext.messageId").value("msg-1"));
            release.countDown(); assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void extractAndRagShareTheSameExternalContractAndForeignClientCannotPoll() throws Exception {
        RagDocument doc = new RagDocument(admin, "policy.txt", UUID.randomUUID().toString(), "text/plain", 4, 100, 10);
        doc.markReady(1); doc = documents.saveAndFlush(doc);
        JsonNode client = client("Company-A", List.of("EXTRACT", "RAG_ANSWER"), List.of(doc.getId()), List.of());
        ObjectNode extract = mapper.createObjectNode().put("capability", "EXTRACT");
        extract.putObject("input").put("text", "phone 01234");
        extract.putObject("configuration").putArray("fields").addObject().put("name", "phone").put("type", "STRING").put("required", true);
        extract.set("externalContext", context()); when(providers.complete(anyString(), anyString(), anyList(), any())).thenReturn("{\"phone\":\"01234\"}");
        task(client, "extract", extract).andExpect(status().isOk()).andExpect(jsonPath("$.result.values.phone").value("01234"))
                .andExpect(jsonPath("$.externalContext.userId").value("9007199254740993"));
        ObjectNode rag = mapper.createObjectNode().put("capability", "RAG_ANSWER");
        rag.putObject("input").put("text", "policy?"); rag.putObject("configuration").putArray("documentIds").add(doc.getId());
        rag.set("externalContext", context());
        var chunks = mapper.createArrayNode(); chunks.addObject().put("documentId", doc.getId()).put("chunkId", "c1").put("filename", "policy.txt").put("content", "30 days");
        when(providers.search(anyString(), anyString(), anyList(), any())).thenReturn(chunks);
        when(providers.complete(anyString(), anyString(), anyList(), any())).thenReturn("{\"answer\":\"30 days\",\"abstain\":false,\"citations\":[\"c1\"]}");
        JsonNode reply = mapper.readTree(task(client, "rag", rag).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.answer").value("30 days")).andReturn().getResponse().getContentAsString());
        JsonNode other = client("Company-B", List.of("RAG_ANSWER"), List.of(doc.getId()), List.of());
        mvc.perform(get("/api/v1/ai/tasks/"+reply.path("taskId").asText()).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
    }

    @Test void publishedBotKeepsDialogStateCoreFallbackAndIdempotentTurns() throws Exception {
        String botId = bot(); JsonNode client = client("Company-A", List.of("BOT_CHAT"), List.of(), List.of(botId));
        ObjectNode start = mapper.createObjectNode().set("externalContext", context());
        JsonNode session = mapper.readTree(start(client, botId, "init", start).andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").doesNotExist()).andReturn().getResponse().getContentAsString());
        String id = session.path("sessionId").asText();
        start(client, botId, "init", start).andExpect(status().isCreated()).andExpect(jsonPath("$.sessionId").value(id));
        assertThat(jdbc.queryForObject("select token_cipher from ai_integration_bot_sessions where session_id=?", String.class, id)).doesNotContain(".");
        botMessage(client, id, "Mấy giờ cửa hàng đóng cửa?", "m1").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.message", org.hamcrest.Matchers.containsString("chi nhánh")));
        botMessage(client, id, "Sài Gòn", "m2").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.message", org.hamcrest.Matchers.containsString("22:00")));
        botMessage(client, id, "Sài Gòn", "m2").andExpect(status().isOk());
        botMessage(client, id, "Hà Nội", "m2").andExpect(status().isConflict());
        when(ragChat.answerForWidget(any(), anyString(), anyList())).thenReturn("CORE answer");
        botMessage(client, id, "Chính sách mới về bảo hành là gì?", "m3").andExpect(status().isOk())
                .andExpect(jsonPath("$.result.message").value("CORE answer"));
        assertThat(jdbc.queryForObject("select count(*) from studio_bot_turns where session_id=?", Integer.class, id)).isEqualTo(3);
        start(client, botId, "init", mapper.createObjectNode().set("externalContext", context().put("userId", "other")))
                .andExpect(status().isConflict());
    }

    @Test void botSessionRejectsOtherCustomerChannelClientExpiredSessionAndRevocation() throws Exception {
        String botId = bot(); JsonNode client = client("Company-A", List.of("BOT_CHAT"), List.of(), List.of(botId));
        String id = mapper.readTree(start(client, botId, "init", mapper.createObjectNode().set("externalContext", context()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("sessionId").asText();
        for (String field : List.of("userId", "conversationId", "channelId", "channel")) {
            ObjectNode body = mapper.createObjectNode().set("externalContext", context().put(field, "other")); body.putObject("input").put("text", "hello");
            mvc.perform(post("/api/v1/ai/tasks/bot-sessions/"+id+"/messages").header("Authorization", bearer(client))
                    .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andExpect(status().isForbidden());
        }
        JsonNode other = client("Company-A", List.of("BOT_CHAT"), List.of(), List.of(botId));
        botMessage(other, id, "hello", "m1").andExpect(status().isNotFound());
        jdbc.update("update studio_bot_sessions set expires_at=? where id=?", Timestamp.from(Instant.now().minusSeconds(5)), id);
        botMessage(client, id, "hello", "m1").andExpect(status().isGone());
        jdbc.update("update ai_integration_clients set active=false where id=?", client.path("id").asText());
        botMessage(client, id, "hello", "m1").andExpect(status().isUnauthorized());
    }

    @Test void botGrantsAndMachineBoundaryCannotBeBypassed() throws Exception {
        String botId = bot(); JsonNode client = client("Company-A", List.of("CLASSIFY"), List.of(), List.of());
        start(client, botId, "denied", mapper.createObjectNode().set("externalContext", context()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CAPABILITY_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/ai/tasks/bots/"+botId+"/sessions").with(jwt().jwt(j -> j.subject(admin.getId())))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        JsonNode granted = client("Company-A", List.of("BOT_CHAT"), List.of(), List.of(botId));
        jdbc.update("delete from ai_integration_bot_grants where client_id=?", granted.path("id").asText());
        start(granted, botId, "revoked-bot", mapper.createObjectNode().set("externalContext", context()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("BOT_NOT_ALLOWED"));
    }

    private ObjectNode context() {
        return mapper.createObjectNode().put("system", "CEP").put("companyId", "Company-A").put("userId", "9007199254740993")
                .put("channel", "zalo").put("channelId", "channel-1").put("conversationId", "conv-1").put("messageId", "msg-1");
    }
    private ObjectNode classify() {
        ObjectNode b = mapper.createObjectNode().put("capability", "CLASSIFY"); b.putObject("input").put("text", "hello");
        b.putObject("configuration").putArray("allowedLabels").add("sales"); b.set("externalContext", context()); return b;
    }
    private JsonNode client(String company, List<String> caps, List<String> docs, List<String> bots) throws Exception {
        ObjectNode b = mapper.createObjectNode().put("name", "CEP");
        b.set("capabilities", mapper.valueToTree(caps)); b.set("documentIds", mapper.valueToTree(docs)); b.set("botIds", mapper.valueToTree(bots));
        if (company != null) b.putObject("externalBinding").put("system", "CEP").put("companyId", company);
        return mapper.readTree(mvc.perform(post("/api/v1/admin/integration-clients")
                .with(jwt().jwt(j -> j.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(b.toString())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }
    private String bearer(JsonNode client) { return "Bearer "+client.path("credential").asText(); }
    private ResultActions task(JsonNode c, String key, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/v1/ai/tasks").header("Authorization", bearer(c)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }
    private String bot() throws Exception {
        var auth = jwt().jwt(j -> j.subject(admin.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        JsonNode b = mapper.readTree(mvc.perform(post("/api/v1/studio/bots").with(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"CEP Bot\",\"locale\":\"vi-VN\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"includeStarterTemplate\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = b.path("bot").path("id").asText();
        mvc.perform(post("/api/v1/studio/bots/"+id+"/publish").with(auth)).andExpect(status().isOk()); return id;
    }
    private ResultActions start(JsonNode c, String bot, String key, JsonNode body) throws Exception {
        return mvc.perform(post("/api/v1/ai/tasks/bots/"+bot+"/sessions").header("Authorization", bearer(c))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }
    private ResultActions botMessage(JsonNode c, String id, String text, String messageId) throws Exception {
        ObjectNode b = mapper.createObjectNode().set("externalContext", context().put("messageId", messageId));
        b.putObject("input").put("text", text);
        return mvc.perform(post("/api/v1/ai/tasks/bot-sessions/"+id+"/messages").header("Authorization", bearer(c))
                .contentType(MediaType.APPLICATION_JSON).content(b.toString()));
    }
}
