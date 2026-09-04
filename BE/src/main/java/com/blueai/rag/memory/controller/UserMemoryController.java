package com.blueai.rag.memory.controller;

import com.blueai.rag.memory.dto.UpsertUserMemoryRequest;
import com.blueai.rag.memory.dto.UserMemoryResponse;
import com.blueai.rag.memory.service.UserMemoryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/me/memories")
public class UserMemoryController {

    private final UserMemoryService memoryService;

    public UserMemoryController(UserMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    List<UserMemoryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return memoryService.list(jwt.getSubject());
    }

    @PutMapping("/{memoryKey}")
    UserMemoryResponse upsert(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String memoryKey,
            @Valid @RequestBody UpsertUserMemoryRequest request
    ) {
        return memoryService.upsert(jwt.getSubject(), memoryKey, request);
    }

    @DeleteMapping("/{memoryKey}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String memoryKey) {
        memoryService.delete(jwt.getSubject(), memoryKey);
        return ResponseEntity.noContent().build();
    }
}
