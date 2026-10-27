package com.blueai.rag.chat.controller;

import com.blueai.rag.chat.dto.AskQuestionRequest;
import com.blueai.rag.chat.dto.RagAnswerResponse;
import com.blueai.rag.chat.service.RagChatService;
import com.blueai.rag.chat.service.AnswerPresentation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/ask")
public class RagChatController {

    private final RagChatService ragChatService;

    public RagChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @PostMapping
    RagAnswerResponse ask(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String conversationId,
            @Valid @RequestBody AskQuestionRequest request
    ) {
        return AnswerPresentation.answer(ragChatService.ask(jwt.getSubject(), conversationId, request));
    }
}
