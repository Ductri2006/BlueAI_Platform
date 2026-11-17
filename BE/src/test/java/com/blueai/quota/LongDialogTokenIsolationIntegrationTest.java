package com.blueai.quota;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagCompletionClient;
import com.blueai.rag.conversation.event.ConversationTurnCompletedEvent;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyList;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:long-dialog-token;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "app.ai-quota.enabled=true", "app.rag.requests-per-user-per-minute=100",
    "app.rag.llm-base-url=http://unused-provider.test", "app.rag.llm-api-key=qa-only", "app.rag.llm-model=qa-model"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class LongDialogTokenIsolationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ConversationMessageRepository messages;
    @Autowired ApplicationEvents events;
    @MockitoBean RagCompletionClient completion;

    @Test void longPureDialogDoesNotScheduleOrChargeBackgroundAiSummaries() throws Exception {
        String email="long-dialog-quota@example.test";
        users.saveAndFlush(new User(email,encoder.encode("QA-Long-Dialog123!"),"QA Dialog",UserRole.ADMIN));
        String token=post("/api/v1/auth/login",null,Map.of("email",email,"password","QA-Long-Dialog123!"),200).get("accessToken").asText();
        String bot=post("/api/v1/studio/bots",token,Map.of("name","Long Dialog QA","template","SALES_DEMO"),201).path("bot").get("id").asText();
        post("/api/v1/studio/bots/"+bot+"/publish",token,Map.of(),200);
        post("/api/v1/studio/bots/"+bot+"/activate",token,Map.of(),200);
        String conversation=post("/api/v1/conversations",token,Map.of("title","Long Dialog QA"),201).get("id").asText();
        // 32 messages exceed the default 20 recent messages + 10-message summary batch.
        for(int index=0;index<16;index++) {
            JsonNode answer=post("/api/v1/conversations/"+conversation+"/ask",token,Map.of("question","chào bạn"),200);
            assertThat(answer.path("assistantMessage").path("evidence").path("origin").asText()).isEqualTo("DIALOG");
        }
        assertThat(messages.countByConversation_Id(conversation)).isEqualTo(32);
        assertThat(events.stream(ConversationTurnCompletedEvent.class).count()).isZero();
        verify(completion,never()).complete(anyList());
        mvc.perform(get("/api/v1/auth/ai-quota").header("Authorization","Bearer "+token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.fiveHour.used").value(0))
            .andExpect(jsonPath("$.weekly.used").value(0)).andExpect(jsonPath("$.fiveHour.resetsAt").isEmpty());
    }

    private JsonNode post(String path,String token,Map<String,?> body,int expected) throws Exception {
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        if(token!=null) request.header("Authorization","Bearer "+token);
        return mapper.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }
}
