package com.blueai.ai.task.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface AiCompletionProvider {
    String complete(
            String systemInstruction,
            String userContent,
            List<Map<String, String>> history,
            Instant deadline);
}
