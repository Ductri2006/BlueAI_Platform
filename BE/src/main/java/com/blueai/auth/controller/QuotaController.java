package com.blueai.auth.controller;

import com.blueai.rag.chat.service.RagChatService;
import com.blueai.studio.config.WidgetProtectionProperties;
import com.blueai.studio.service.WidgetProtectionMetrics;
import com.blueai.studio.service.WidgetRateLimiter.QuotaStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuotaController {
    private final RagChatService ragChatService;
    private final WidgetProtectionProperties widget;
    private final WidgetProtectionMetrics metrics;
    private final int loginLimit;
    private final int defaultApiLimit;
    private com.blueai.quota.AiTokenQuotaService tokenQuota;

    @org.springframework.beans.factory.annotation.Autowired
    public void setTokenQuota(com.blueai.quota.AiTokenQuotaService tokenQuota) { this.tokenQuota = tokenQuota; }

    @GetMapping("/api/v1/auth/ai-quota")
    ResponseEntity<com.blueai.quota.AiTokenQuotaService.Snapshot> aiQuota(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(tokenQuota.snapshot(jwt.getSubject()));
    }

    public QuotaController(RagChatService ragChatService, WidgetProtectionProperties widget,
                           WidgetProtectionMetrics metrics,
                           @Value("${app.auth.login-attempts-per-minute:20}") int loginLimit,
                           @Value("${APP_API_KEY_REQUESTS_PER_MINUTE:30}") int defaultApiLimit) {
        this.ragChatService = ragChatService;
        this.widget = widget;
        this.metrics = metrics;
        this.loginLimit = loginLimit;
        this.defaultApiLimit = defaultApiLimit;
    }

    @GetMapping("/api/v1/auth/quota")
    ResponseEntity<QuotaStatus> currentQuota(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ragChatService.quota(jwt.getSubject()));
    }

    public record QuotaOverview(QuotaStatus chat, int loginAttemptsPerMinute, int defaultApiRequestsPerMinute,
                                int widgetMessagesPerWindowPerClient, long widgetWindowSeconds,
                                double blockedRequestsOnThisInstance) { }

    @GetMapping("/api/v1/admin/quotas")
    ResponseEntity<QuotaOverview> overview(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new QuotaOverview(
                ragChatService.quota(jwt.getSubject()), loginLimit, defaultApiLimit,
                widget.getMaxMessagesPerWindowPerClient(), widget.getRateLimitWindow().toSeconds(), metrics.blockedRequests()));
    }
}
