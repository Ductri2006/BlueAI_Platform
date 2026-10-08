package com.blueai.ai.task.controller;

import com.blueai.ai.task.model.AiClientPrincipal;
import com.blueai.ai.task.service.AiTaskService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai/tasks")
public class AiTaskController {
    private final AiTaskService tasks;

    public AiTaskController(AiTaskService tasks) {
        this.tasks = tasks;
    }

    @PostMapping
    public ResponseEntity<?> createTask(
            @AuthenticationPrincipal AiClientPrincipal client,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody JsonNode body) {
        return tasks.submit(client, key, body);
    }

    @GetMapping("/{id}")
    public Map<String, Object> getTask(
            @AuthenticationPrincipal AiClientPrincipal client, @PathVariable String id) {
        return tasks.get(client, id);
    }
}
