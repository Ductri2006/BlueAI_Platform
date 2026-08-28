package com.blueai.rag.conversation.controller;

import com.blueai.rag.conversation.dto.ConversationResponse;
import com.blueai.rag.conversation.dto.CreateConversationRequest;
import com.blueai.rag.conversation.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping
    ResponseEntity<ConversationResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateConversationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationService.create(jwt.getSubject(), request));
    }

    @GetMapping
    List<ConversationResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return conversationService.list(jwt.getSubject());
    }

    @GetMapping("/{conversationId}")
    ConversationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String conversationId) {
        return conversationService.get(jwt.getSubject(), conversationId);
    }
}
