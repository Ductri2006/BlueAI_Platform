package com.blueai.quota;

import com.blueai.auth.model.User;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:usage-pool-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.hikari.connection-timeout=500", "app.ai-quota.enabled=true",
    "app.rag.retrieval-base-url=http://pool-provider.test", "app.rag.llm-base-url=http://pool-provider.test",
    "app.rag.llm-api-key=qa-only", "app.rag.llm-model=gemini-3.5-flash-lite"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsagePoolIsolationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired HikariDataSource mainPool;
    @MockitoBean RagRestClientFactory factory;

    @Test void admissionSettlementAndUsageRecordingWorkWhenTheChatOwnsTheLastMainConnection() throws Exception {
        var owner=users.saveAndFlush(new User("usage-pool@example.test","unused-qa-hash","Pool QA"));
        var identity=jwt().jwt(value->value.subject(owner.getId())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
        var builder=RestClient.builder();
        var provider=MockRestServiceServer.bindTo(builder).build();
        when(factory.create()).thenAnswer(call->builder.clone());
        provider.expect(requestTo("http://pool-provider.test/v1/retrieval/search"))
            .andRespond(withSuccess("{\"matches\":[],\"embeddingTokens\":8,\"embeddingCountMethod\":\"COUNT_TOKENS\"}",MediaType.APPLICATION_JSON));
        provider.expect(requestTo("http://pool-provider.test/v1/chat/completions"))
            .andRespond(withSuccess("{\"model\":\"gemini-3.5-flash-lite\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"QA answer\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":30}}",MediaType.APPLICATION_JSON));
        String created=mvc.perform(post("/api/v1/conversations").with(identity).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"QA last main connection\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String conversation=mapper.readTree(created).get("id").asText();
        long globalBefore=jdbc.queryForObject("SELECT COALESCE(SUM(total_tokens),0) FROM gemini_usage_events",Long.class);
        // Flyway can use multiple startup connections. Restrict only the running application's pool.
        for(int attempt=0;attempt<500 && mainPool.getHikariPoolMXBean().getActiveConnections()>0;attempt++) Thread.sleep(10);
        assertThat(mainPool.getHikariPoolMXBean().getActiveConnections()).isZero();
        mainPool.setMinimumIdle(0);
        mainPool.setMaximumPoolSize(1);
        mainPool.getHikariPoolMXBean().softEvictConnections();
        mvc.perform(post("/api/v1/conversations/"+conversation+"/ask").with(identity).contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"QA connection isolation\"}")).andExpect(status().isOk());
        provider.verify();
        mvc.perform(get("/api/v1/auth/ai-quota").with(identity)).andExpect(status().isOk())
            .andExpect(jsonPath("$.fiveHour.used").value(38)).andExpect(jsonPath("$.fiveHour.reserved").value(0));
        assertThat(jdbc.queryForObject("SELECT SUM(total_tokens) FROM gemini_usage_events",Long.class)-globalBefore).isEqualTo(30);
    }

    @Test void tenAccountsCanCompleteChatAndSettleUsageConcurrently() throws Exception {
        mainPool.setMaximumPoolSize(10);
        mainPool.setMinimumIdle(0);
        var builder=RestClient.builder();
        var provider=MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        when(factory.create()).thenAnswer(call->builder.clone());
        provider.expect(times(10),requestTo("http://pool-provider.test/v1/retrieval/search"))
            .andRespond(withSuccess("{\"matches\":[],\"embeddingTokens\":8,\"embeddingCountMethod\":\"COUNT_TOKENS\"}",MediaType.APPLICATION_JSON));
        provider.expect(times(10),requestTo("http://pool-provider.test/v1/chat/completions"))
            .andRespond(request->{
                // A bounded fake provider delay keeps real chat transactions occupied together.
                try { Thread.sleep(100); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
                return withSuccess("{\"model\":\"gemini-3.5-flash-lite\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Concurrent QA answer\"}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":30}}",MediaType.APPLICATION_JSON).createResponse(request);
            });
        var start=new CountDownLatch(1);
        var ready=new CountDownLatch(10);
        var calls=new ArrayList<Callable<Void>>();
        for(int index=0;index<10;index++) {
            var owner=users.saveAndFlush(new User("parallel-pool-"+index+"@example.test","unused-qa-hash","Parallel QA"));
            String ownerId=owner.getId();
            var identity=jwt().jwt(value->value.subject(ownerId)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
            String body=mvc.perform(post("/api/v1/conversations").with(identity).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Parallel QA\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String conversation=mapper.readTree(body).get("id").asText();
            calls.add(()->{
                ready.countDown();
                assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();
                mvc.perform(post("/api/v1/conversations/"+conversation+"/ask").with(identity).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"Parallel QA\"}")).andExpect(status().isOk());
                mvc.perform(get("/api/v1/auth/ai-quota").with(identity)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.fiveHour.used").value(38)).andExpect(jsonPath("$.fiveHour.reserved").value(0));
                return null;
            });
        }
        long globalBefore=jdbc.queryForObject("SELECT COALESCE(SUM(total_tokens),0) FROM gemini_usage_events",Long.class);
        try(var executor=Executors.newFixedThreadPool(10)) {
            var results=calls.stream().map(executor::submit).toList();
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for(var result:results) result.get(10,TimeUnit.SECONDS);
        }
        provider.verify();
        assertThat(jdbc.queryForObject("SELECT SUM(total_tokens) FROM gemini_usage_events",Long.class)-globalBefore).isEqualTo(300);
    }
}
