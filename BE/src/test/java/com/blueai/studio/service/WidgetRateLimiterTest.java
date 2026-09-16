package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetRateLimiterTest {

    @Test
    void rejectsRequestsAfterTheConfiguredWindowLimit() {
        WidgetRateLimiter limiter = new WidgetRateLimiter();

        limiter.check("client:127.0.0.1", 2, Duration.ofMinutes(1), "slow down");
        limiter.check("client:127.0.0.1", 2, Duration.ofMinutes(1), "slow down");

        assertThatThrownBy(() -> limiter.check(
                "client:127.0.0.1", 2, Duration.ofMinutes(1), "slow down"
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiException = (ApiException) error;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(apiException.getCode()).isEqualTo("WIDGET_RATE_LIMITED");
                });
    }

    @Test
    void keepsCountersSeparatePerKey() {
        WidgetRateLimiter limiter = new WidgetRateLimiter();

        limiter.check("client:a", 1, Duration.ofMinutes(1), "slow down");
        limiter.check("client:b", 1, Duration.ofMinutes(1), "slow down");

        assertThat(limiter.trackedWindowCount()).isEqualTo(2);
    }
}
