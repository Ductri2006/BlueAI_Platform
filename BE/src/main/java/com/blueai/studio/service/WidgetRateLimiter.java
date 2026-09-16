package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small per-instance limiter for the public widget endpoints.
 *
 * <p>The widget is intentionally unauthenticated, so a session token alone is
 * not enough protection: an attacker could otherwise create many sessions and
 * spend the shared LLM quota. This limiter is deliberately independent from
 * browser CORS and must be replaced or backed by a shared store when the API is
 * deployed behind multiple instances.</p>
 */
@Service
public class WidgetRateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public void check(String key, int maxRequests, Duration window, String message) {
        Instant now = Instant.now();
        Window updated = windows.compute(key, (ignored, current) -> {
            if (current == null || !current.expiresAt().isAfter(now)) {
                return new Window(now.plus(window), 1);
            }
            return new Window(current.expiresAt(), current.count() + 1);
        });

        if (updated.count() > maxRequests) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "WIDGET_RATE_LIMITED", message);
        }

        // Keep attacker-controlled client addresses/session ids from growing the
        // map without bound. This is only a local safety valve; distributed
        // deployments still need a shared gateway limiter.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
    }

    int trackedWindowCount() {
        return windows.size();
    }

    private record Window(Instant expiresAt, int count) {
    }
}
