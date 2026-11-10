package com.blueai.auth.controller;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.studio.service.WidgetRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.rag.requests-per-user-per-minute=2",
        "spring.datasource.url=jdbc:h2:mem:quota-tests;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ObjectMapper mapper;
    @Autowired PasswordEncoder encoder;

    @Test
    void quotaIsPrivateReadOnlyAndUsesTheSameCounterAsAsk() throws Exception {
        String user = login(UserRole.USER);
        String other = login(UserRole.USER);
        mvc.perform(get("/api/v1/auth/quota")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/quotas").header("Authorization", user)).andExpect(status().isForbidden());
        for (int index = 0; index < 3; index++) {
            mvc.perform(get("/api/v1/auth/quota").header("Authorization", user))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.limit").value(2)).andExpect(jsonPath("$.remaining").value(2));
        }
        String path = "/api/v1/conversations/" + UUID.randomUUID() + "/ask";
        for (int index = 0; index < 2; index++) {
            mvc.perform(post(path).header("Authorization", user).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"QA quota\"}")).andExpect(status().isNotFound());
        }
        mvc.perform(post(path).header("Authorization", user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"QA quota\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RAG_RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
        mvc.perform(get("/api/v1/auth/quota").header("Authorization", user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(0)).andExpect(jsonPath("$.resetsAt").isNotEmpty());
        mvc.perform(get("/api/v1/auth/quota").header("Authorization", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(2));
    }

    @Test
    void onlyAdminCanSeePolicyAndOperationalCounts() throws Exception {
        mvc.perform(get("/api/v1/admin/quotas").header("Authorization", login(UserRole.ADMIN)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.chat.limit").value(2))
                .andExpect(jsonPath("$.loginAttemptsPerMinute").value(20))
                .andExpect(jsonPath("$.blockedRequestsOnThisInstance").isNumber());
    }

    @Test
    void dialogPreviewAndAskShareQuotaAndEachPreviewCountsOnce() throws Exception {
        String admin = login(UserRole.ADMIN);
        JsonNode bot = mapper.readTree(mvc.perform(post("/api/v1/studio/bots").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"QA quota\",\"template\":\"SALES_DEMO\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String preview = "/api/v1/studio/bots/" + bot.path("bot").path("id").asText() + "/preview";
        mvc.perform(post(preview).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Xin chào\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/quota").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.remaining").value(1));
        mvc.perform(post("/api/v1/conversations/" + UUID.randomUUID() + "/ask").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"QA quota\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(preview).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Xin chào\"}")).andExpect(status().isTooManyRequests());
    }

    private String login(UserRole role) throws Exception {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", encoder.encode("QuotaPassword123!"), "QA", role));
        JsonNode session = mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", user.getEmail(), "password", "QuotaPassword123!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return "Bearer " + session.get("accessToken").asText();
    }
}
