package com.blueai.studio.controller;

import com.blueai.studio.dto.BotStudioDtos.CreateWidgetSessionResponse;
import com.blueai.studio.dto.BotStudioDtos.RuntimeMessageRequest;
import com.blueai.studio.dto.BotStudioDtos.RuntimeMessageResponse;
import com.blueai.studio.service.BotRuntimeService;
import com.blueai.studio.service.WidgetClientAddressResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class BotRuntimeController {

    private final BotRuntimeService runtimeService;
    private final WidgetClientAddressResolver clientAddressResolver;

    public BotRuntimeController(
            BotRuntimeService runtimeService,
            WidgetClientAddressResolver clientAddressResolver
    ) {
        this.runtimeService = runtimeService;
        this.clientAddressResolver = clientAddressResolver;
    }

    @PostMapping("/api/v1/widget/{publicKey}/sessions")
    ResponseEntity<CreateWidgetSessionResponse> createSession(
            @PathVariable String publicKey,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(runtimeService.createSession(publicKey, clientAddressResolver.resolve(request)));
    }

    @PostMapping("/api/v1/runtime/sessions/{sessionId}/messages")
    RuntimeMessageResponse message(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Widget-Token", required = false) String accessToken,
            @Valid @RequestBody RuntimeMessageRequest request,
            HttpServletRequest httpRequest
    ) {
        return runtimeService.message(sessionId, accessToken, request, clientAddressResolver.resolve(httpRequest));
    }
}
