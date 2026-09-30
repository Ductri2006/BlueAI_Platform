package com.blueai.ai.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.time.Instant;

public interface AiRetrievalProvider {
    JsonNode search(String ownerId, String query, List<String> allowedDocumentIds, Instant deadline);
}
