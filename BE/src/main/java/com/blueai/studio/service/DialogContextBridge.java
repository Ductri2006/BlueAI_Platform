package com.blueai.studio.service;

import com.blueai.rag.context.ContextSourceType;
import com.blueai.rag.context.ResolvedConversationContext;
import com.blueai.rag.memory.model.MemoryStatus;
import com.blueai.studio.dto.BotStudioDtos.EntityInput;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Selects bounded, unambiguous hints. The runtime must still ask for confirmation. */
public final class DialogContextBridge {
    public record Suggestion(String value, String source) { }

    public Map<String, Suggestion> suggestions(BotSnapshot snapshot, ResolvedConversationContext context) {
        Map<String, Suggestion> result = new LinkedHashMap<>();
        for (EntityInput entity : snapshot.entities()) {
            Map<String, String> candidates = new LinkedHashMap<>();
            context.memories().stream()
                    .filter(memory -> memory.getStatus() == MemoryStatus.CONFIRMED)
                    .filter(memory -> memory.getExpiresAt() == null || memory.getExpiresAt().isAfter(Instant.now()))
                    .filter(memory -> mentionsEntity(memory.getMemoryKey(), entity.name()))
                    .forEach(memory -> collect(entity, memory.getContent(), "Ghi nhớ đã xác nhận: " + memory.getMemoryKey(), candidates));
            context.relatedContexts().stream()
                    .filter(fragment -> fragment.type() == ContextSourceType.LINKED_CONVERSATION)
                    .forEach(fragment -> collect(entity, fragment.content(), "Phiên được liên kết: " + fragment.title(), candidates));
            if (candidates.size() == 1) {
                var candidate = candidates.entrySet().iterator().next();
                result.put(entity.name(), new Suggestion(candidate.getKey(), candidate.getValue()));
            }
        }
        return Map.copyOf(result);
    }

    private boolean mentionsEntity(String key, String entity) {
        return (" " + DialogRuntimeService.normalize(key) + " ")
                .contains(" " + DialogRuntimeService.normalize(entity) + " ");
    }

    private void collect(EntityInput entity, String content, String source, Map<String, String> candidates) {
        String text = " " + DialogRuntimeService.normalize(content) + " ";
        Set<String> values = new LinkedHashSet<>();
        for (var value : entity.values()) {
            if (matches(text, value.value()) || (value.synonyms() != null && value.synonyms().stream().anyMatch(alias -> matches(text, alias)))) {
                values.add(value.value());
            }
        }
        values.forEach(value -> candidates.putIfAbsent(value, source));
    }

    private boolean matches(String text, String alias) {
        String normalized = DialogRuntimeService.normalize(alias);
        return !normalized.isBlank() && text.contains(" " + normalized + " ");
    }
}
