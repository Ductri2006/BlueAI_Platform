package com.blueai.ai.task.controller;

import com.blueai.ai.task.service.IntegrationClientService;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/integration-clients")
public class IntegrationClientController {
    private final IntegrationClientService clients;

    public IntegrationClientController(IntegrationClientService clients) {
        this.clients = clients;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createClient(
            @AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(clients.createClient(jwt.getSubject(), body));
    }

    @GetMapping
    public List<Map<String, Object>> listClients(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return clients.listClients(jwt.getSubject(), page, size);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        clients.revoke(jwt.getSubject(), id);
        return ResponseEntity.noContent().build();
    }
}
