package com.blueai.rag.conversation.service;

import com.blueai.rag.context.ContextBudgetPolicy;
import com.blueai.rag.context.ContextBudgetProperties;
import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationContextSnapshotFactoryTest {
    @Test
    void snapshotKeepsNewestCorrectionWhenBudgetsTruncateOldHistory() {
        var properties = new ContextBudgetProperties();
        properties.setLinkedConversationCharacters(300);
        var policy = new ContextBudgetPolicy(properties);
        var messages = mock(ConversationMessageRepository.class);
        var summaries = mock(ConversationSummaryRepository.class);
        var conversation = mock(Conversation.class);
        when(conversation.getId()).thenReturn("source");
        when(conversation.getTitle()).thenReturn("Hikari");
        var newest = new ConversationMessage(conversation, MessageRole.USER, "Số lượng mới nhất là 90 eSIM.");
        var older = new ConversationMessage(conversation, MessageRole.USER, "Thông tin cũ ".repeat(100) + "75 eSIM");
        when(messages.findByConversation_IdOrderByCreatedAtDescIdDesc(eq("source"), any()))
                .thenReturn(List.of(newest, older));
        when(summaries.findByConversation_Id("source")).thenReturn(Optional.empty());

        var snapshot = new ConversationContextSnapshotFactory(summaries, messages, policy).capture(conversation);

        assertThat(snapshot.content()).hasSizeLessThanOrEqualTo(300).contains("90 eSIM").doesNotContain("75 eSIM");
        assertThat(snapshot.lastMessageId()).isEqualTo(newest.getId());
        assertThat(policy.limit(snapshot.content(), 200)).contains("90 eSIM");
    }
}
