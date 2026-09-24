package com.blueai.rag.conversation.service;

import com.blueai.BlueAiAuthApplication;
import com.blueai.auth.model.User;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagRestClientFactory;
import com.blueai.rag.conversation.event.ConversationTurnCompletedEvent;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.ConversationSummary;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.conversation.repository.ConversationLinkRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@ActiveProfiles("test")
class ConversationSummaryServiceIntegrationTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ConversationLinkRepository linkRepository;

    @Autowired
    private UserMemoryRepository memoryRepository;

    @Autowired
    private ConversationSummaryRepository summaryRepository;

    @Autowired
    private ConversationMessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private RagRestClientFactory restClientFactory;

    private MockRestServiceServer ragStub;

    @DynamicPropertySource
    static void summaryProperties(DynamicPropertyRegistry registry) {
        registry.add("app.rag.llm-base-url", () -> "http://summary-stub.test");
        registry.add("app.rag.llm-api-key", () -> "summary-test-key");
        registry.add("app.rag.llm-model", () -> "summary-test-model");
        registry.add("app.rag.context.max-recent-messages", () -> "2");
        registry.add("app.rag.context.summary-batch-size", () -> "2");
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        ragStub = MockRestServiceServer.bindTo(builder).build();
        when(restClientFactory.create()).thenAnswer(invocation -> builder.clone());
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void summarizesOlderTurnsAsynchronouslyWithoutBlockingTheConversationTransaction() throws Exception {
        User owner = userRepository.saveAndFlush(new User("summary-owner@example.com", "hash", "Summary Owner"));
        Conversation conversation = conversationRepository.saveAndFlush(new Conversation(owner, "Tư vấn khách hàng"));
        messageRepository.saveAndFlush(new ConversationMessage(conversation, MessageRole.USER, "Khách hàng cần tư vấn eSIM Hàn Quốc."));
        messageRepository.saveAndFlush(new ConversationMessage(conversation, MessageRole.ASSISTANT, "Đã ghi nhận nhu cầu eSIM Hàn Quốc."));
        messageRepository.saveAndFlush(new ConversationMessage(conversation, MessageRole.USER, "Khách đi năm ngày và ưu tiên dữ liệu."));
        messageRepository.saveAndFlush(new ConversationMessage(conversation, MessageRole.ASSISTANT, "Đang kiểm tra gói phù hợp."));

        ragStub.expect(once(), requestTo("http://summary-stub.test/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.messages[1].content", org.hamcrest.Matchers.containsString("New conversation messages")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Khách hàng cần eSIM Hàn Quốc, đi năm ngày và ưu tiên dữ liệu."}}]}
                        """, MediaType.APPLICATION_JSON));

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> eventPublisher.publishEvent(new ConversationTurnCompletedEvent(conversation.getId()))
        );

        ConversationSummary summary = awaitSummary(conversation.getId());
        assertThat(summary.getContent()).isEqualTo("Khách hàng cần eSIM Hàn Quốc, đi năm ngày và ưu tiên dữ liệu.");
        assertThat(summary.getCoveredMessageCount()).isEqualTo(2);
        ragStub.verify();
    }

    private ConversationSummary awaitSummary(String conversationId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        Optional<ConversationSummary> summary;
        do {
            summary = summaryRepository.findByConversation_Id(conversationId);
            if (summary.isPresent()) return summary.get();
            Thread.sleep(25);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Timed out waiting for the background conversation summary.");
    }

    private void cleanDatabase() {
        linkRepository.deleteAll();
        memoryRepository.deleteAll();
        summaryRepository.deleteAll();
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();
    }
}
