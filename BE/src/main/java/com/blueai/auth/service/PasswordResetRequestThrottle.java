package com.blueai.auth.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded local throttle. Production deployments with multiple replicas
 * should replace this seam with a shared Redis-backed limiter.
 */
@Component
public class PasswordResetRequestThrottle {

    private static final int MAX_KEYS = 10_000;
    private final ConcurrentHashMap<String, Instant> expiryByKey = new ConcurrentHashMap<>();
    private final ReentrantLock admissionLock = new ReentrantLock();
    private final Clock clock;
    private Instant nextCleanupAt = Instant.MAX;
    private long cleanupSweepCount;

    public PasswordResetRequestThrottle() {
        this(Clock.systemUTC());
    }

    PasswordResetRequestThrottle(Clock clock) {
        this.clock = clock;
    }

    public boolean allow(String email, Duration cooldown) {
        Instant now = Instant.now(clock);
        String key = fingerprint(email);
        admissionLock.lock();
        try {
            Instant previousExpiry = expiryByKey.get(key);
            if (previousExpiry != null && previousExpiry.isAfter(now)) {
                return false;
            }
            if (previousExpiry == null && expiryByKey.size() >= MAX_KEYS) {
                // Cleanup is deliberately outside any ConcurrentHashMap.compute
                // mapping function. Mutating the same map from that callback can
                // cause recursive-update failures under high-cardinality load.
                if (!now.isBefore(nextCleanupAt)) {
                    removeExpired(now);
                }
                if (expiryByKey.size() >= MAX_KEYS) {
                    return false;
                }
            }
            Instant expiry = now.plus(cooldown);
            expiryByKey.put(key, expiry);
            if (expiry.isBefore(nextCleanupAt)) {
                nextCleanupAt = expiry;
            }
            return true;
        } finally {
            admissionLock.unlock();
        }
    }

    private void removeExpired(Instant now) {
        cleanupSweepCount++;
        Instant earliestExpiry = Instant.MAX;
        for (var entry : expiryByKey.entrySet()) {
            if (!entry.getValue().isAfter(now)) {
                expiryByKey.remove(entry.getKey(), entry.getValue());
            } else if (entry.getValue().isBefore(earliestExpiry)) {
                earliestExpiry = entry.getValue();
            }
        }
        nextCleanupAt = earliestExpiry;
    }

    // Package-private observability for the bounded-sweep regression test.
    long cleanupSweepCount() {
        return cleanupSweepCount;
    }

    private String fingerprint(String email) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }
}
