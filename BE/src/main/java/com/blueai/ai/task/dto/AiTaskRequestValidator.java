package com.blueai.ai.task.dto;

import com.blueai.ai.task.exception.AiTaskException;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;

import java.util.*;

/** Strict bounded request validation, shared by controller and task service. */
public final class AiTaskRequestValidator {
    private AiTaskRequestValidator() {}

    public static void requireKeys(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject())
            throw bad("INVALID_REQUEST", "Request must be a JSON object.");
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!allowed.contains(name))
                                throw bad("UNKNOWN_FIELD", "Unknown request field: " + name);
                        });
    }

    public static String requiredText(JsonNode node, String name, int max) {
        JsonNode v = node.get(name);
        if (v == null || !v.isTextual() || v.asText().isBlank() || v.asText().length() > max)
            throw bad(
                    "INVALID_REQUEST",
                    "Field " + name + " must be non-empty and at most " + max + " characters.");
        return v.asText();
    }

    public static Set<String> stringSet(JsonNode n, Set<String> allowed, int max) {
        if (n == null || n.isMissingNode() || n.isNull()) return Set.of();
        if (!n.isArray() || n.size() > max)
            throw bad("INVALID_REQUEST", "Expected a bounded array.");
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode v : n) {
            if (!v.isTextual() || v.asText().isBlank())
                throw bad("INVALID_REQUEST", "Array values must be text.");
            if (allowed != null && !allowed.contains(v.asText()))
                throw bad("INVALID_REQUEST", "Unsupported value.");
            values.add(v.asText());
        }
        if (values.size() != n.size())
            throw bad("INVALID_REQUEST", "Duplicate array values are not allowed.");
        return values;
    }

    public static AiTaskException bad(String code, String message) {
        return new AiTaskException(HttpStatus.BAD_REQUEST, code, message);
    }
}
