package com.blueai.studio.controller;

import com.blueai.studio.dto.BotStudioDtos.BotConfigurationResponse;
import com.blueai.studio.dto.BotStudioDtos.BotSummaryResponse;
import com.blueai.studio.dto.BotStudioDtos.CreateBotRequest;
import com.blueai.studio.dto.BotStudioDtos.PreviewRequest;
import com.blueai.studio.dto.BotStudioDtos.PublishResponse;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;
import com.blueai.studio.dto.BotStudioDtos.SaveConfigurationRequest;
import com.blueai.studio.service.BotStudioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/studio/bots")
public class BotStudioController {

    private final BotStudioService botStudioService;

    public BotStudioController(BotStudioService botStudioService) {
        this.botStudioService = botStudioService;
    }

    @GetMapping
    List<BotSummaryResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return botStudioService.list(jwt.getSubject());
    }

    @PostMapping
    ResponseEntity<BotConfigurationResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateBotRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(botStudioService.create(jwt.getSubject(), request));
    }

    @GetMapping("/{botId}")
    BotConfigurationResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String botId) {
        return botStudioService.get(jwt.getSubject(), botId);
    }

    @PutMapping("/{botId}/configuration")
    BotConfigurationResponse save(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String botId,
            @Valid @RequestBody SaveConfigurationRequest request
    ) {
        return botStudioService.save(jwt.getSubject(), botId, request);
    }

    @PostMapping("/{botId}/preview")
    RuntimeResponse preview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String botId,
            @Valid @RequestBody PreviewRequest request
    ) {
        return botStudioService.preview(jwt.getSubject(), botId, request);
    }

    @PostMapping("/{botId}/publish")
    PublishResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable String botId) {
        return botStudioService.publish(jwt.getSubject(), botId);
    }
}
