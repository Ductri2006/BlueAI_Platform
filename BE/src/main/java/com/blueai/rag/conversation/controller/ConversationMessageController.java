package com.blueai.rag.conversation.controller;

import com.blueai.rag.conversation.dto.ConversationMessageResponse;
import com.blueai.rag.conversation.dto.CreateUserMessageRequest;
import com.blueai.rag.conversation.service.ConversationMessageService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public class ConversationMessageController {

    private final ConversationMessageService messageService;

    public ConversationMessageController(ConversationMessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    ResponseEntity<ConversationMessageResponse> addUserMessage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody CreateUserMessageRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                messageService.addUserMessage(jwt.getSubject(), conversationId, request)
        );
    }

    @GetMapping
    List<ConversationMessageResponse> listRecentMessages(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return messageService.listRecentMessages(jwt.getSubject(), conversationId, limit);
    }
}
