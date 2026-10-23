package com.blueai.ai.task.controller;

import com.blueai.ai.task.model.AiClientPrincipal;
import com.blueai.ai.task.service.IntegrationBotService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Kept under the existing machine-only security matcher; human JWTs cannot enter this API. */
@RestController
@RequestMapping("/api/v1/ai/tasks")
public class IntegrationBotController {
    private final IntegrationBotService bots;
    public IntegrationBotController(IntegrationBotService bots) { this.bots = bots; }

    @PostMapping("/bots/{botId}/sessions")
    public ResponseEntity<?> create(@AuthenticationPrincipal AiClientPrincipal client,
            @PathVariable String botId, @RequestHeader(value="Idempotency-Key", required=false) String key,
            @RequestBody JsonNode body) {
        return ResponseEntity.status(201).body(bots.create(client, botId, key, body));
    }

    @PostMapping("/bot-sessions/{sessionId}/messages")
    public Map<String, Object> message(@AuthenticationPrincipal AiClientPrincipal client,
            @PathVariable String sessionId, @RequestBody JsonNode body) {
        return bots.message(client, sessionId, body);
    }
}
