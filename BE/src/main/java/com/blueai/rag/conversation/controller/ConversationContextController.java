package com.blueai.rag.conversation.controller;

import com.blueai.rag.conversation.dto.ConversationContextSettingsRequest;
import com.blueai.rag.conversation.dto.ConversationContextSettingsResponse;
import com.blueai.rag.conversation.dto.ConversationLinkResponse;
import com.blueai.rag.conversation.dto.CreateConversationLinkRequest;
import com.blueai.rag.conversation.service.ConversationLinkService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}")
public class ConversationContextController {

    private final ConversationLinkService linkService;

    public ConversationContextController(ConversationLinkService linkService) {
        this.linkService = linkService;
    }

    @GetMapping("/context")
    ConversationContextSettingsResponse getSettings(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId
    ) {
        return linkService.getSettings(jwt.getSubject(), conversationId);
    }

    @PutMapping("/context")
    ConversationContextSettingsResponse updateSettings(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody ConversationContextSettingsRequest request
    ) {
        return linkService.updateSettings(jwt.getSubject(), conversationId, request);
    }

    @PostMapping("/context-links")
    ResponseEntity<ConversationLinkResponse> createLink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody CreateConversationLinkRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                linkService.createLink(jwt.getSubject(), conversationId, request.sourceConversationId())
        );
    }

    @PostMapping("/context-links/{linkId}/refresh")
    ConversationLinkResponse refreshLink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @PathVariable String linkId
    ) {
        return linkService.refreshLink(jwt.getSubject(), conversationId, linkId);
    }

    @DeleteMapping("/context-links/{linkId}")
    ResponseEntity<Void> deleteLink(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @PathVariable String linkId
    ) {
        linkService.deleteLink(jwt.getSubject(), conversationId, linkId);
        return ResponseEntity.noContent().build();
    }
}
