package com.blueai.ai.task.controller;

import com.blueai.ai.task.exception.AiTaskException;
import com.blueai.ai.task.model.PublishedApiPrincipal;
import com.blueai.ai.task.service.PublishedBotApiService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class PublishedBotApiController {
    private final PublishedBotApiService api;
    public PublishedBotApiController(PublishedBotApiService api) { this.api=api; }

    @GetMapping("/api/v1/studio/bots/{botId}/api-key")
    public ResponseEntity<?> settings(@AuthenticationPrincipal Jwt jwt, @PathVariable String botId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(api.settings(jwt.getSubject(),botId));
    }
    @PutMapping("/api/v1/studio/bots/{botId}/api-key-settings")
    public ResponseEntity<?> save(@AuthenticationPrincipal Jwt jwt, @PathVariable String botId, @RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(api.saveSettings(jwt.getSubject(),botId,body));
    }
    @PostMapping("/api/v1/ai/tasks/chat")
    public Map<String,Object> chat(@AuthenticationPrincipal Object principal, @RequestBody JsonNode body) {
        if (!(principal instanceof PublishedApiPrincipal key)) throw new AiTaskException(
                HttpStatus.FORBIDDEN,"PUBLISHED_BOT_KEY_REQUIRED","Use the API key copied from the bot's Custom page.");
        return api.chat(key,body);
    }
}
