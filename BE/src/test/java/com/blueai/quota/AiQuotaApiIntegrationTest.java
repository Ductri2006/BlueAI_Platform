package com.blueai.quota;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
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

@SpringBootTest(properties={"app.ai-quota.enabled=true", "spring.datasource.url=jdbc:h2:mem:token-api;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiQuotaApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired AiTokenQuotaService quota;

    @Test void quotaIsPrivatePerCallerAndReadDoesNotStartAWindow() throws Exception {
        mvc.perform(get("/api/v1/auth/ai-quota")).andExpect(status().isUnauthorized());
        for (UserRole role : UserRole.values()) {
            String email = UUID.randomUUID()+"@example.test";
            users.saveAndFlush(new User(email, encoder.encode("QA-Token-Password123!"), "Token QA", role));
            var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email,"password","QA-Token-Password123!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String bearer = "Bearer "+mapper.readTree(response).get("accessToken").asText();
            mvc.perform(get("/api/v1/auth/ai-quota?userId=another-account").header("Authorization",bearer))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.fiveHour.used").value(0))
                .andExpect(jsonPath("$.fiveHour.resetsAt").isEmpty()).andExpect(jsonPath("$.weekly.limit").value(1000000));
        }
    }

    @Test void workerAccountingRequiresThePrivateWorkerCredential() throws Exception {
        mvc.perform(post("/api/v1/internal/documents/ai-quota/reserve").contentType(MediaType.APPLICATION_JSON)
            .content("{\"inputBound\":10}" )).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/documents/ai-quota/reserve").header("X-Worker-Api-Key","wrong")
            .contentType(MediaType.APPLICATION_JSON).content("{\"inputBound\":10}" )).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/documents/ai-quota/reserve").header("X-Worker-Api-Key","test-worker-api-key-for-integration-tests")
            .contentType(MediaType.APPLICATION_JSON).content("{\"inputBound\":10}" ))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNotEmpty());
    }
}
