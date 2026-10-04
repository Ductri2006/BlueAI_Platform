package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.studio.config.WidgetProtectionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WidgetRateLimiterTest {

    @Test
    void rejectsRequestsAfterTheConfiguredWindowLimit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WidgetRateLimiter limiter = new WidgetRateLimiter(
                null,
                new WidgetProtectionProperties(),
                new WidgetProtectionMetrics(registry)
        );

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
        assertThat(registry.find(WidgetProtectionMetrics.METRIC_NAME)
                .tags("event", "rate_limited", "scope", "rate_limit")
                .counter()
                .count()).isEqualTo(1);
    }

    @Test
    void keepsCountersSeparatePerKey() {
        WidgetRateLimiter limiter = new WidgetRateLimiter(
                null,
                new WidgetProtectionProperties(),
                new WidgetProtectionMetrics(new SimpleMeterRegistry())
        );

        limiter.check("client:a", 1, Duration.ofMinutes(1), "slow down");
        limiter.check("client:b", 1, Duration.ofMinutes(1), "slow down");

        assertThat(limiter.trackedWindowCount()).isEqualTo(2);
    }

    @Test
    void enabledRedisWithoutTemplateFailsClosedInsteadOfUsingLocalCounters() {
        var properties = new WidgetProtectionProperties();
        properties.setRedisEnabled(true);
        var limiter = new WidgetRateLimiter(null, properties, new WidgetProtectionMetrics(new SimpleMeterRegistry()));
        assertThatThrownBy(() -> limiter.check("session:fixture", 10, Duration.ofMinutes(1), "slow down"))
                .isInstanceOf(ApiException.class).satisfies(error -> {
                    assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((ApiException) error).getCode()).isEqualTo("WIDGET_PROTECTION_UNAVAILABLE");
                });
        assertThat(limiter.trackedWindowCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureAndNullResultsFailClosedButRecoveryAllowsRequests() {
        var properties = new WidgetProtectionProperties();
        properties.setRedisEnabled(true);
        var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var redis = org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(redis);
        org.mockito.Mockito.when(redis.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class),
                        org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.<Object>any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret-fixture"))
                .thenReturn(null).thenReturn(1L).thenReturn(2L);
        var limiter = new WidgetRateLimiter(provider, properties, new WidgetProtectionMetrics(new SimpleMeterRegistry()));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> limiter.check("message-session:fixture", 1, Duration.ofMinutes(1), "slow down"))
                    .isInstanceOf(ApiException.class).hasMessageNotContaining("secret-fixture")
                    .satisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        }
        limiter.check("message-session:fixture", 1, Duration.ofMinutes(1), "slow down");
        assertThatThrownBy(() -> limiter.check("message-session:fixture", 1, Duration.ofMinutes(1), "slow down"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(limiter.trackedWindowCount()).isZero();
    }

    @Test
    void localCounterMemoryHasAHardBoundWithoutEvictingActiveQuota() {
        var limiter = new WidgetRateLimiter(null, new WidgetProtectionProperties(),
                new WidgetProtectionMetrics(new SimpleMeterRegistry()));
        for (int key = 0; key < 10_000; key++) {
            limiter.check("session:" + key, 1, Duration.ofHours(1), "slow down");
        }
        assertThatThrownBy(() -> limiter.check("session:new", 1, Duration.ofHours(1), "slow down"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(limiter.trackedWindowCount()).isEqualTo(10_000);
        assertThatThrownBy(() -> limiter.check("session:0", 1, Duration.ofHours(1), "slow down"))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
