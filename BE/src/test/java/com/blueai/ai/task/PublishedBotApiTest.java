package com.blueai.ai.task;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagChatService;
import com.blueai.studio.model.Bot;
import com.blueai.studio.repository.BotRepository;
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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BlueAiAuthApplication.class, properties = {
        "app.widget.protection.max-session-creations-per-window=1",
        "app.widget.protection.max-messages-per-window-per-client=1",
        "app.widget.protection.max-messages-per-window-per-session=1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishedBotApiTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:published-bot-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired BotRepository bots;
    @MockitoBean RagChatService rag;
    private User owner;

    @BeforeEach void fixture() {
        owner = users.save(new User(UUID.randomUUID()+"@published.test", "unused", "Key owner", UserRole.ADMIN));
        reset(rag); when(rag.answerForWidget(any(), anyString(), anyList())).thenReturn("Core reply");
    }

    @Test void firstPublishGeneratesACopyableEncryptedKeyAndRepublishKeepsIt() throws Exception {
        String bot = bot();
        settings(bot, owner).andExpect(status().isOk()).andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.requestsPerMinute").value(30));
        publish(bot);
        JsonNode first = key(bot);
        assertThat(first.path("apiKey").asText()).startsWith("blueai_").hasSizeGreaterThan(40);
        String persisted = jdbc.queryForObject("select credential_cipher from studio_bot_api_keys where bot_id=?", String.class, bot);
        assertThat(persisted).doesNotContain(first.path("apiKey").asText());
        publish(bot);
        assertThat(key(bot).path("apiKey")).isEqualTo(first.path("apiKey"));
        assertThat(jdbc.queryForObject("select count(*) from studio_bot_api_keys where bot_id=?", Integer.class, bot)).isEqualTo(1);
        settings(bot, owner).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void perKeyLimitIsPublishedSharedAcrossCustomersAndResetsAfterItsWindow() throws Exception {
        String bot = bot(); publish(bot); String key = key(bot).path("apiKey").asText();
        saveLimit(bot, 2).andExpect(status().isOk()).andExpect(jsonPath("$.requestsPerMinute").value(2))
                .andExpect(jsonPath("$.publishedRequestsPerMinute").value(30));
        publish(bot);
        chat(key, "customer-a", "Xin chào", "1").andExpect(status().isOk());
        chat(key, "customer-b", "Xin chào", "1").andExpect(status().isOk());
        chat(key, "customer-c", "Xin chào", "1").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.error.code").value("API_KEY_RATE_LIMITED"));
        String other = bot(); publish(other);
        chat(key(other).path("apiKey").asText(), "customer-c", "Xin chào", "1").andExpect(status().isOk());
        jdbc.update("update studio_bot_api_keys set rate_window_start=? where bot_id=?", Timestamp.from(Instant.now().minusSeconds(61)), bot);
        chat(key, "customer-c", "Xin chào", "2").andExpect(status().isOk());
    }

    @Test void simpleRequestsKeepSeparateCustomerHistoryAndReplayTheSameMessage() throws Exception {
        String bot = bot(); publish(bot); String apiKey = key(bot).path("apiKey").asText();
        JsonNode first = mapper.readTree(chat(apiKey, "9007199254740993", "Mấy giờ cửa hàng đóng cửa?", "m1")
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value("9007199254740993"))
                .andExpect(jsonPath("$.answer", org.hamcrest.Matchers.containsString("chi nhánh")))
                .andReturn().getResponse().getContentAsString());
        chat(apiKey, "9007199254740993", "Sài Gòn", "m2").andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", org.hamcrest.Matchers.containsString("22:00")));
        chat(apiKey, "9007199254740993", "Sài Gòn", "m2").andExpect(status().isOk());
        chat(apiKey, "9007199254740993", "Hà Nội", "m2").andExpect(status().isConflict());
        JsonNode other = mapper.readTree(chat(apiKey, "other", "Xin chào", "m1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(other.path("sessionId")).isNotEqualTo(first.path("sessionId"));
        assertThat(jdbc.queryForObject("select count(*) from studio_bot_turns where session_id=?", Integer.class,
                first.path("sessionId").asText())).isEqualTo(2);
        mvc.perform(post("/api/v1/ai/tasks/chat").header("Authorization", "Bearer "+apiKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"minimal\",\"message\":\"Xin chào\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messageId").isNotEmpty());
    }

    @Test void publishedRateReplacesWidgetRateAndDuplicateGuardsForMachineCalls() throws Exception {
        String bot = bot(); saveLimit(bot, 20).andExpect(status().isOk()); publish(bot);
        String key = key(bot).path("apiKey").asText();
        for (int i=0; i<15; i++) chat(key, "same-customer", "Xin chào", "message-"+i).andExpect(status().isOk());
    }

    @Test void invalidKeysAndKeyScopeCannotReachAdministrativeOrTaskApis() throws Exception {
        String bot = bot(); publish(bot); String key = key(bot).path("apiKey").asText();
        chat("wrong-key", "u", "hello", "m").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ai/tasks/chat").with(jwt().jwt(j -> j.subject(owner.getId())))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/studio/bots/"+bot+"/api-key").header("Authorization", "Bearer "+key)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/ai/tasks").header("Authorization", "Bearer "+key)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        User other = users.save(new User(UUID.randomUUID()+"@other.test", "unused", "Regular user", UserRole.USER));
        settings(bot, other).andExpect(status().isForbidden());
    }

    @Test void adminsShareExistingKeyAndQuotaSettingsWithoutChangingItsOwnerOrCustomerIsolation() throws Exception {
        String bot = bot(); publish(bot);
        String originalKey = key(bot).path("apiKey").asText();
        User other = users.saveAndFlush(new User(UUID.randomUUID()+"@admin.test", "unused", "Another admin", UserRole.ADMIN));
        settings(bot, other).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.apiKey").value(originalKey));
        mvc.perform(put("/api/v1/studio/bots/"+bot+"/api-key-settings").with(jwt().jwt(j -> j.subject(other.getId()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestsPerMinute\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestsPerMinute").value(2))
                .andExpect(jsonPath("$.publishedRequestsPerMinute").value(30));
        assertThat(key(bot).path("requestsPerMinute").asInt()).isEqualTo(2);
        mvc.perform(post("/api/v1/studio/bots/"+bot+"/publish").with(jwt().jwt(j -> j.subject(other.getId()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isOk());
        assertThat(key(bot).path("apiKey").asText()).isEqualTo(originalKey);
        settings(bot, other).andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").value(originalKey))
                .andExpect(jsonPath("$.publishedRequestsPerMinute").value(2));
        assertThat(bots.findById(bot).orElseThrow().getOwner().getId()).isEqualTo(owner.getId());
        assertThat(jdbc.queryForObject("select count(*) from studio_bot_api_keys where bot_id=?", Integer.class, bot)).isEqualTo(1);

        JsonNode first = mapper.readTree(chat(originalKey, "customer-a", "Xin chào", "m1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode second = mapper.readTree(chat(originalKey, "customer-b", "Xin chào", "m1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(first.path("sessionId")).isNotEqualTo(second.path("sessionId"));
        chat(originalKey, "customer-c", "Xin chào", "m1").andExpect(status().isTooManyRequests());
    }

    @Test void sharedManagementRejectsDisabledDeletedAndNonAdminAccountsAndPrivateUserBots() throws Exception {
        String bot = bot(); publish(bot);
        User disabled = users.saveAndFlush(new User(UUID.randomUUID()+"@disabled.test", "unused", "Disabled", UserRole.ADMIN));
        disabled.changeStatus(UserStatus.DISABLED); users.saveAndFlush(disabled);
        User deleted = users.saveAndFlush(new User(UUID.randomUUID()+"@deleted.test", "unused", "Deleted", UserRole.ADMIN));
        deleted.softDelete(Instant.now()); users.saveAndFlush(deleted);
        User regular = users.saveAndFlush(new User(UUID.randomUUID()+"@regular.test", "unused", "Regular", UserRole.USER));
        for (User denied : java.util.List.of(disabled, deleted, regular)) {
            settings(bot, denied).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/studio/bots/"+bot+"/api-key-settings").with(jwt().jwt(j -> j.subject(denied.getId()))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestsPerMinute\":5}")).andExpect(status().isForbidden());
        }
        Bot privateBot = bots.saveAndFlush(new Bot(regular, "Private legacy bot", null, "vi-VN", "Asia/Ho_Chi_Minh"));
        settings(privateBot.getId(), owner).andExpect(status().isForbidden());
        assertThat(key(bot).path("requestsPerMinute").asInt()).isEqualTo(30);
    }

    @Test void limitsRejectInvalidNumbersAndDisablingOwnerInvalidatesTheKey() throws Exception {
        String bot = bot();
        for (String value : new String[]{"0", "1001", "1.5", "\"5\"", "null"}) {
            mvc.perform(put("/api/v1/studio/bots/"+bot+"/api-key-settings").with(jwt().jwt(j -> j.subject(owner.getId()))
                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"requestsPerMinute\":"+value+"}")).andExpect(status().isBadRequest());
        }
        publish(bot); String key = key(bot).path("apiKey").asText();
        owner.changeStatus(UserStatus.DISABLED); users.saveAndFlush(owner);
        chat(key, "user", "hello", "m").andExpect(status().isUnauthorized());
    }

    @Test void concurrentRequestsUseOneDatabaseCounterAcrossTheKey() throws Exception {
        String bot = bot(); saveLimit(bot, 3).andExpect(status().isOk()); publish(bot); String key = key(bot).path("apiKey").asText();
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = IntStream.range(0, 8).mapToObj(i -> executor.submit(() ->
                    chat(key, "user-"+i, "Xin chào", "m").andReturn().getResponse().getStatus())).toList();
            int ok=0, limited=0;
            for (var future : futures) { int status=future.get(10, TimeUnit.SECONDS); if (status==200) ok++; else if (status==429) limited++; }
            assertThat(ok).isEqualTo(3); assertThat(limited).isEqualTo(5);
        } finally { executor.shutdownNow(); }
    }

    private String bot() throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/studio/bots").with(jwt().jwt(j -> j.subject(owner.getId()))
                        .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"API Bot "+UUID.randomUUID()+"\",\"locale\":\"vi-VN\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"includeStarterTemplate\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("bot").path("id").asText();
    }
    private void publish(String id) throws Exception {
        mvc.perform(post("/api/v1/studio/bots/"+id+"/publish").with(jwt().jwt(j -> j.subject(owner.getId()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isOk());
    }
    private ResultActions settings(String id, User user) throws Exception {
        return mvc.perform(get("/api/v1/studio/bots/"+id+"/api-key").with(jwt().jwt(j -> j.subject(user.getId()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    private JsonNode key(String id) throws Exception { return mapper.readTree(settings(id,owner).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    private ResultActions saveLimit(String id, int limit) throws Exception {
        return mvc.perform(put("/api/v1/studio/bots/"+id+"/api-key-settings").with(jwt().jwt(j -> j.subject(owner.getId()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestsPerMinute\":"+limit+"}"));
    }
    private ResultActions chat(String key, String user, String message, String messageId) throws Exception {
        ObjectNode body=mapper.createObjectNode().put("userId",user).put("message",message).put("messageId",messageId);
        return mvc.perform(post("/api/v1/ai/tasks/chat").header("Authorization","Bearer "+key)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }
}
