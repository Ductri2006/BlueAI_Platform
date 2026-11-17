package com.blueai.quota;

import com.blueai.rag.chat.RagChatProperties;
import com.blueai.rag.chat.service.*;
import com.blueai.system.service.GeminiUsageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AiQuotaCompletionTransportTest {
    @Test void successfulGenerationSettlesActualUsageAndPropagatesTheOutputCap() {
        var fixture = new AiTokenQuotaServiceTest();
        fixture.setup();
        var properties = fixture.properties;
        var quota = fixture.quota;
        properties.setFiveHourTokens(10000);
        properties.setWeeklyTokens(10000);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var factory = mock(RagRestClientFactory.class);
        when(factory.create()).thenAnswer(call -> builder.clone());
        var config = new RagChatProperties();
        config.setLlmBaseUrl("http://worker.test");
        config.setLlmApiKey("qa-key");
        config.setLlmModel("gemini-3.5-flash-lite");
        var client = new RagCompletionClient(config, factory, mock(GeminiUsageService.class), "qa-worker", URI.create("http://worker.test"));
        client.setTokenQuota(quota);
        server.expect(requestTo("http://worker.test/v1/chat/completions"))
            .andExpect(jsonPath("$.max_tokens").value(2048)).andExpect(jsonPath("$.operation_id").isNotEmpty())
            .andRespond(withSuccess("{\"model\":\"gemini-3.5-flash-lite\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Answer\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":20}}", MediaType.APPLICATION_JSON));
        try (var ignored = AiUsageActor.forOwner("one")) {
            assertThat(client.complete(List.of(new LlmChatMessage("user","hello")))).isEqualTo("Answer");
        }
        assertThat(quota.snapshot("one").fiveHour().used()).isEqualTo(20);
        assertThat(quota.snapshot("one").fiveHour().reserved()).isZero();
        server.verify();
    }
}
