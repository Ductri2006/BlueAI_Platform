package com.blueai.rag.conversation.service;

import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.context.ContextBudgetPolicy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/** Creates a bounded, read-only snapshot that can safely be linked elsewhere. */
@Component
public class ConversationContextSnapshotFactory {

    private final ConversationSummaryRepository summaryRepository;
    private final ConversationMessageRepository messageRepository;
    private final ContextBudgetPolicy budgetPolicy;

    public ConversationContextSnapshotFactory(
            ConversationSummaryRepository summaryRepository,
            ConversationMessageRepository messageRepository,
            ContextBudgetPolicy budgetPolicy
    ) {
        this.summaryRepository = summaryRepository;
        this.messageRepository = messageRepository;
        this.budgetPolicy = budgetPolicy;
    }

    public Snapshot capture(Conversation conversation) {
        StringBuilder content = new StringBuilder("Hội thoại nguồn: ")
                .append(conversation.getTitle()).append('\n');
        List<ConversationMessage> recent = messageRepository
                .findByConversation_IdOrderByCreatedAtDescIdDesc(
                        conversation.getId(),
                        PageRequest.of(0, budgetPolicy.getSnapshotMessageCount())
                );
        if (!recent.isEmpty()) {
            // Downstream budgets also truncate prefixes. Put newest messages
            // first so neither linked nor relevant-history budgets discard updates.
            content.append("Trao đổi gần đây (mới nhất trước; ưu tiên cập nhật USER mới nhất):\n");
            for (ConversationMessage message : recent) {
                content.append(message.getRole()).append(": ")
                        .append(budgetPolicy.limit(message.getContent(), budgetPolicy.getSnapshotMessageCharacters()))
                        .append('\n');
            }
        }

        summaryRepository.findByConversation_Id(conversation.getId())
                .map(summary -> budgetPolicy.limit(summary.getContent(), budgetPolicy.getSummaryCharacters()))
                .filter(summary -> !summary.isBlank())
                .ifPresent(summary -> content.append("\nTóm tắt trước đó do AI tạo:\n").append(summary));

        String snapshot = budgetPolicy.limit(content.toString(), budgetPolicy.getLinkedConversationCharacters());
        String lastMessageId = recent.isEmpty() ? null : recent.getFirst().getId();
        return new Snapshot(snapshot, lastMessageId);
    }

    public record Snapshot(String content, String lastMessageId) {
    }
}
