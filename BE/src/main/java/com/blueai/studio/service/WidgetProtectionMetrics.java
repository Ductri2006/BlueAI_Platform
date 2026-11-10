package com.blueai.studio.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

/**
 * Emits a deliberately low-cardinality operational signal for public-widget
 * protection. Event and scope are enums so a client address, session id,
 * request id, token, or message can never accidentally become a metric tag.
 */
@Service
public class WidgetProtectionMetrics {

    public static final String METRIC_NAME = "blueai.widget.protection.events";

    private final MeterRegistry meterRegistry;

    public WidgetProtectionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(Event event, Scope scope) {
        meterRegistry.counter(
                METRIC_NAME,
                "event", event.tagValue(),
                "scope", scope.tagValue()
        ).increment();
    }

    public double blockedRequests() {
        return meterRegistry.find(METRIC_NAME).tag("event", Event.RATE_LIMITED.tagValue())
                .counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    public enum Event {
        RATE_LIMITED("rate_limited"),
        REDIS_FALLBACK("redis_fallback"),
        IDEMPOTENCY_REPLAY("idempotency_replay"),
        IDEMPOTENCY_CONFLICT("idempotency_conflict"),
        DUPLICATE_REPLAY("duplicate_replay"),
        DUPLICATE_BLOCKED("duplicate_blocked"),
        SESSION_TURN_LIMIT("session_turn_limit"),
        REQUEST_IN_PROGRESS("request_in_progress");

        private final String tagValue;

        Event(String tagValue) {
            this.tagValue = tagValue;
        }

        String tagValue() {
            return tagValue;
        }
    }

    public enum Scope {
        SESSION_CREATION("session_creation"),
        MESSAGE_CLIENT("message_client"),
        MESSAGE_SESSION("message_session"),
        MESSAGE("message"),
        RATE_LIMIT("rate_limit"),
        CONTEXT("context"),
        REQUEST_LOCK("request_lock");

        private final String tagValue;

        Scope(String tagValue) {
            this.tagValue = tagValue;
        }

        String tagValue() {
            return tagValue;
        }
    }
}
