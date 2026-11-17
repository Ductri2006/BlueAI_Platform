package com.blueai.quota;

import com.blueai.auth.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Private worker-only admission for KB indexing. No user id is accepted from the worker. */
@RestController
@RequestMapping("/api/v1/internal/documents/ai-quota")
public class WorkerQuotaController {
    private final AiTokenQuotaService quota;
    private final byte[] key;
    public WorkerQuotaController(AiTokenQuotaService quota, @Value("${app.worker.api-key}") String key) {
        this.quota = quota;
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }
    public record Input(@Min(1) @Max(30000) long inputBound) { }
    public record Usage(@NotNull AiTokenQuotaService.Reservation reservation,
                        @Min(0) long promptTokens, String measurement) { }
    @PostMapping("/reserve")
    AiTokenQuotaService.Reservation reserve(@RequestHeader(value="X-Worker-Api-Key", required=false) String provided,
                                           @Valid @RequestBody Input input) {
        requireKey(provided);
        return quota.reserve("EMBEDDING", input.inputBound(), 0);
    }
    @PostMapping("/confirm")
    void confirm(@RequestHeader(value="X-Worker-Api-Key", required=false) String provided,
                 @Valid @RequestBody Usage usage) {
        requireKey(provided);
        if ("COUNT_TOKENS".equals(usage.measurement())) quota.confirmCountedInput(usage.reservation(), usage.promptTokens());
        else quota.confirm(usage.reservation(), usage.promptTokens(), 0, usage.promptTokens());
    }
    private void requireKey(String provided) {
        if (key.length == 0 || new String(key, StandardCharsets.UTF_8).equals("local-worker-api-key-change-before-production"))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WORKER_API_KEY_NOT_CONFIGURED", "Worker authentication is not configured.");
        if (!MessageDigest.isEqual(key, provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8)))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_WORKER_API_KEY", "Worker API key is invalid.");
    }
}
