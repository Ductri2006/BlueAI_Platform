package com.blueai.ai.task.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public interface AiRetrievalProvider {
    JsonNode search(
            String ownerId, String query, List<String> allowedDocumentIds, Instant deadline);
}
