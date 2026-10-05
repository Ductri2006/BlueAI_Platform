package com.blueai.rag.conversation.service;

import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.conversation.repository.ConversationLinkRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationServiceRetentionTest {

    @Test
    void disabledRetentionDoesNotTouchTheRepository() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        ConversationService service = service(conversations, false, Duration.ofDays(30), 7);

        assertThat(service.runRetentionCleanup(Instant.parse("2026-09-24T00:00:00Z"))).isZero();
        verifyNoInteractions(conversations);
    }

    @Test
    void enabledRetentionUsesConfiguredClockBoundaryAndBatch() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        var expired = List.of(new com.blueai.rag.conversation.model.Conversation(null, "expired"));
        when(conversations.findDeletedBefore(now.minus(Duration.ofDays(30)), PageRequest.of(0, 7)))
                .thenReturn(expired);
        ConversationService service = service(conversations, true, Duration.ofDays(30), 7);

        assertThat(service.runRetentionCleanup(now)).isEqualTo(1);
        verify(conversations).findDeletedBefore(now.minus(Duration.ofDays(30)), PageRequest.of(0, 7));
        verify(conversations).deleteAll(expired);
    }

    private ConversationService service(
            ConversationRepository conversations,
            boolean enabled,
            Duration retention,
            int batchSize
    ) {
        return new ConversationService(
                mock(UserRepository.class),
                conversations,
                mock(ConversationMessageRepository.class),
                mock(ConversationSummaryRepository.class),
                mock(ConversationLinkRepository.class),
                mock(UserMemoryRepository.class),
                enabled,
                retention,
                batchSize
        );
    }
}
