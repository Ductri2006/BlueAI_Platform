package com.blueai.rag.context;

import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.memory.model.UserMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetPolicyTest {

    @Test
    void truncatesEachHistoryMessageAndNeverExceedsTheTotalCharacterBudget() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setHistoryCharacters(10);
        properties.setSnapshotMessageCharacters(6);
        properties.setMaxRecentMessages(3);
        ContextBudgetPolicy policy = new ContextBudgetPolicy(properties);

        List<ConversationHistoryMessage> history = policy.keepRecentHistory(List.of(
                new ConversationMessage(null, MessageRole.USER, "123456789"),
                new ConversationMessage(null, MessageRole.ASSISTANT, "abcdefghi")
        ));

        assertThat(history).hasSize(2);
        assertThat(history).allSatisfy(message -> assertThat(message.content().length()).isLessThanOrEqualTo(6));
        assertThat(history.stream().mapToInt(message -> message.content().length()).sum()).isLessThanOrEqualTo(10);
        assertThat(history.getFirst().role()).isEqualTo(MessageRole.USER);
        assertThat(history.getLast().role()).isEqualTo(MessageRole.ASSISTANT);
    }

    @Test
    void skipsAnOversizedMemoryWhenItCannotFitTheConfiguredBudget() {
        ContextBudgetProperties properties = new ContextBudgetProperties();
        properties.setMemoryCharacters(500);
        ContextBudgetPolicy policy = new ContextBudgetPolicy(properties);

        assertThat(policy.keepMemories(List.of(
                new UserMemory(null, "large-preference", "x".repeat(1_000))
        ))).isEmpty();
    }
}
