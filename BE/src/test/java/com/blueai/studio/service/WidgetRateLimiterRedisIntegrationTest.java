package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.studio.config.WidgetProtectionProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "BLUEAI_QA_REDIS_PORT", matches = "[0-9]+")
class WidgetRateLimiterRedisIntegrationTest {
    @Test
    void twoApiInstancesShareAtomicQuotaAndReadOnlyTtl() throws Exception {
        var connection = new JedisConnectionFactory(new RedisStandaloneConfiguration(
                "127.0.0.1", Integer.parseInt(System.getenv("BLUEAI_QA_REDIS_PORT"))));
        connection.afterPropertiesSet();
        connection.start();
        var redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        var provider = new StaticListableBeanFactory();
        provider.addBean("redis", redis);
        var properties = new WidgetProtectionProperties();
        properties.setRedisEnabled(true);
        properties.setRedisKeyPrefix("blueai:qa:" + UUID.randomUUID() + ":");
        var metrics = new WidgetProtectionMetrics(new SimpleMeterRegistry());
        var first = new WidgetRateLimiter(provider.getBeanProvider(StringRedisTemplate.class), properties, metrics);
        var second = new WidgetRateLimiter(provider.getBeanProvider(StringRedisTemplate.class), properties, metrics);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var calls = IntStream.range(0, 12).mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                try {
                    (index % 2 == 0 ? first : second).check("rag-user:qa", 3, Duration.ofMinutes(1), "limited");
                    return true;
                } catch (ApiException limited) {
                    assertThat(limited.getStatus().value()).isEqualTo(429);
                    assertThat(limited.getRetryAfterSeconds()).isBetween(1L, 60L);
                    return false;
                }
            }, pool)).toList();
            assertThat(calls.stream().filter(CompletableFuture::join).count()).isEqualTo(3);
            var before = first.snapshot("rag-user:qa", 3, Duration.ofMinutes(1));
            Thread.sleep(40);
            var after = second.snapshot("rag-user:qa", 3, Duration.ofMinutes(1));
            assertThat(after.remaining()).isZero();
            assertThat(after.resetsAt()).isBetween(before.resetsAt().minusMillis(30), before.resetsAt().plusMillis(30));
            assertThat(first.snapshot("rag-user:other", 3, Duration.ofMinutes(1)).remaining()).isEqualTo(3);
        } finally {
            // All fixture keys are uniquely prefixed and expire within one minute.
            connection.destroy();
        }
    }
}
