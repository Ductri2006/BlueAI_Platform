package com.blueai.rag.context;

import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.memory.model.UserMemory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class ContextBudgetPolicy {

    private final ContextBudgetProperties properties;

    public ContextBudgetPolicy(ContextBudgetProperties properties) {
        this.properties = properties;
    }

    public List<ConversationHistoryMessage> keepRecentHistory(List<ConversationMessage> chronologicalMessages) {
        List<ConversationHistoryMessage> selected = new ArrayList<>();
        int remaining = properties.getHistoryCharacters();
        for (int index = chronologicalMessages.size() - 1; index >= 0 && selected.size() < properties.getMaxRecentMessages(); index--) {
            ConversationMessage message = chronologicalMessages.get(index);
            if (remaining <= 0) break;
            int messageBudget = Math.min(remaining, properties.getSnapshotMessageCharacters());
            String boundedContent = limit(message.getContent(), messageBudget);
            if (boundedContent.isBlank()) continue;
            selected.add(new ConversationHistoryMessage(message.getRole(), boundedContent));
            remaining -= boundedContent.length();
        }
        Collections.reverse(selected);
        return selected;
    }

    public List<ConversationContextFragment> keepFragments(
            List<ConversationContextFragment> candidates,
            int characterBudget
    ) {
        List<ConversationContextFragment> selected = new ArrayList<>();
        int remaining = characterBudget;
        for (ConversationContextFragment candidate : candidates) {
            if (remaining <= 0) break;
            String content = limit(candidate.content(), remaining);
            if (content.isBlank()) continue;
            selected.add(candidate.withContent(content));
            remaining -= content.length();
        }
        return selected;
    }

    public List<UserMemory> keepMemories(List<UserMemory> candidates) {
        List<UserMemory> selected = new ArrayList<>();
        int remaining = properties.getMemoryCharacters();
        for (UserMemory candidate : candidates) {
            if (remaining <= 0) break;
            int cost = Math.min(candidate.getContent().length(), 1_000);
            // UserMemory is an entity and must not be mutated while resolving
            // a prompt. Skip a memory that cannot fit rather than letting a
            // single oversized value violate the configured total budget.
            if (cost > remaining) continue;
            selected.add(candidate);
            remaining -= cost;
        }
        return selected;
    }

    public String limit(String value, int maximumCharacters) {
        if (value == null || value.isBlank() || maximumCharacters <= 0) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= maximumCharacters) return trimmed;
        if (maximumCharacters <= 3) return trimmed.substring(0, maximumCharacters);
        return trimmed.substring(0, maximumCharacters - 3) + "...";
    }

    public int getSummaryCharacters() { return properties.getSummaryCharacters(); }
    public int getLinkedConversationCharacters() { return properties.getLinkedConversationCharacters(); }
    public int getRelatedHistoryCharacters() { return properties.getRelatedHistoryCharacters(); }
    public int getSnapshotMessageCount() { return properties.getSnapshotMessageCount(); }
    public int getSnapshotMessageCharacters() { return properties.getSnapshotMessageCharacters(); }
    public int getAutomaticHistoryLimit() { return properties.getAutomaticHistoryLimit(); }
    public double getMinimumRelevanceScore() { return properties.getMinimumRelevanceScore(); }
    public int getSummaryBatchSize() { return properties.getSummaryBatchSize(); }
    public int getMaxRecentMessages() { return properties.getMaxRecentMessages(); }
}
