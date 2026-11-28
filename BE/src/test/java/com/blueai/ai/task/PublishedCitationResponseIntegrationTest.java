package com.blueai.ai.task;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BlueAiAuthApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:public-citations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishedCitationResponseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RagChatService rag;

    @Test void thirdPartyChatRemovesCitationsForNewAndPreviouslyCachedResponses() throws Exception {
        var owner = users.save(new User(UUID.randomUUID() + "@citation.test", "unused", "Owner", UserRole.ADMIN));
        var auth = jwt().jwt(token -> token.subject(owner.getId())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        String botId = mapper.readTree(mvc.perform(post("/api/v1/studio/bots").with(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Citation test\",\"includeStarterTemplate\":true}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("bot").path("id").asText();
        mvc.perform(post("/api/v1/studio/bots/" + botId + "/publish").with(auth)).andExpect(status().isOk());
        String apiKey = mapper.readTree(mvc.perform(get("/api/v1/studio/bots/" + botId + "/api-key").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("apiKey").asText();
        String raw = "**Cài đặt** > <b>Thêm eSIM</b> **[S4]**.\n\nDùng Wi-Fi ổn định [S2, S4].";
        String clean = "Cài đặt > Thêm eSIM.\n\nDùng Wi-Fi ổn định.";
        when(rag.answerForWidget(any(), anyString(), anyList(), any())).thenReturn(raw);
        String body = "{\"userId\":\"customer\",\"message\":\"Samsung có dùng eSIM không?\",\"messageId\":\"same-message\"}";
        JsonNode response = mapper.readTree(mvc.perform(post("/api/v1/ai/tasks/chat").header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(clean))
                .andReturn().getResponse().getContentAsString());
        String session = response.path("sessionId").asText();
        // Simulate a pre-fix stored turn; replay must also pass through the public DTO boundary.
        var legacy = mapper.readTree(jdbc.queryForObject("SELECT response_json FROM studio_bot_turns WHERE session_id=?", String.class, session));
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy.path("result")).put("message", raw);
        jdbc.update("UPDATE studio_bot_turns SET response_json=? WHERE session_id=?", mapper.writeValueAsString(legacy), session);
        mvc.perform(post("/api/v1/ai/tasks/chat").header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(clean));
        verify(rag, times(1)).answerForWidget(any(), anyString(), anyList(), any());
        assertThat(jdbc.queryForObject("SELECT state_json FROM studio_bot_sessions WHERE id=?", String.class, session))
                .doesNotContain("[S2, S4]", "**", "<b>");
    }
}
