package com.blueai.ai.task;

import java.util.List;
import java.util.Map;
import java.time.Instant;

public interface AiCompletionProvider {
    String complete(String systemInstruction, String userContent, List<Map<String,String>> history, Instant deadline);
}
