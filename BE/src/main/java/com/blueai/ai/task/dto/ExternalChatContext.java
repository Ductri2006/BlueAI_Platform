package com.blueai.ai.task.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** External routing identifiers are correlation data, never authorization claims or model input. */
public final class ExternalChatContext {
    private ExternalChatContext() {}

    public static Map<String, String> binding(JsonNode node) {
        if (node.isMissingNode()) return Map.of();
        AiTaskRequestValidator.requireKeys(node, Set.of("system", "companyId"));
        return Map.of("system", identifier(node, "system", 40),
                "companyId", identifier(node, "companyId", 128));
    }

    public static Map<String, String> parse(JsonNode node) {
        if (node.isMissingNode()) return Map.of();
        AiTaskRequestValidator.requireKeys(node, Set.of(
                "system", "companyId", "userId", "channel", "channelId", "conversationId", "messageId"));
        Map<String, String> context = new LinkedHashMap<>();
        for (String key : new String[]{"system", "companyId", "userId", "channel", "channelId", "conversationId", "messageId"}) {
            context.put(key, identifier(node, key, key.equals("system") || key.equals("channel") ? 40 : 128));
        }
        return Map.copyOf(context);
    }

    private static String identifier(JsonNode node, String key, int max) {
        String value = AiTaskRequestValidator.requiredText(node, key, max);
        if (!value.equals(value.strip()) || value.codePoints().anyMatch(Character::isISOControl)) {
            throw AiTaskRequestValidator.bad("INVALID_EXTERNAL_IDENTIFIER",
                    "External identifiers must not contain control characters or surrounding whitespace.");
        }
        return value;
    }
}
