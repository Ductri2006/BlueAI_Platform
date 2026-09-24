package com.blueai.auth.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Clock;
import java.time.Instant;
import java.util.Properties;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;

class PasswordResetDeliveryTest {

    @Test
    void smtpAdapterSendsLinkWithoutExposingAnythingThroughApi() {
        CapturingGateway gateway = new CapturingGateway();
        new SmtpPasswordResetDelivery(gateway, "no-reply@example.test", "https://app.example.test/reset-password")
                .deliver("user@example.test", "one-time-token");

        assertThat(gateway.to).isEqualTo("user@example.test");
        assertThat(gateway.subject).contains("Reset");
        assertThat(gateway.text).contains("token=one-time-token");
    }

    @Test
    void springMailAdapterPopulatesMimeMessageUsingFakeJavaMailSender() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);

        new SpringMailSenderGateway(sender).send(
                "no-reply@example.test", "user@example.test", "Reset", "https://example.test/?token=abc"
        );

        verify(sender).send(message);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("user@example.test");
        assertThat(message.getSubject()).isEqualTo("Reset");
        assertThat(message.getContent().toString()).contains("token=abc");
    }

    @Test
    void throttleAllowsOnlyOneConcurrentRequestForTheSameAddress() throws Exception {
        PasswordResetRequestThrottle throttle = new PasswordResetRequestThrottle();
        int workers = 16;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < workers; i++) {
            executor.submit(() -> {
                start.await();
                if (throttle.allow("same@example.test", java.time.Duration.ofMinutes(5))) allowed.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(allowed).hasValue(1);
    }

    @Test
    void throttleStaysBoundedAndStableWithConcurrentHighCardinalityKeys() throws Exception {
        PasswordResetRequestThrottle throttle = new PasswordResetRequestThrottle();
        int attempts = 10_500;
        ExecutorService executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>(attempts);
        for (int i = 0; i < attempts; i++) {
            int key = i;
            futures.add(executor.submit(() -> {
                try {
                    start.await();
                    if (throttle.allow("high-cardinality-" + key + "@example.test", java.time.Duration.ofHours(1))) {
                        allowed.incrementAndGet();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } catch (RuntimeException exception) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(failures).hasValue(0);
        assertThat(allowed).hasValue(10_000);
    }

    @Test
    void throttleDoesNotRescanAtCapacityBeforeNextExpiry() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        PasswordResetRequestThrottle throttle = new PasswordResetRequestThrottle(clock);

        for (int i = 0; i < 9_999; i++) {
            assertThat(throttle.allow("long-lived-" + i + "@example.test", java.time.Duration.ofHours(1))).isTrue();
        }
        assertThat(throttle.allow("short-lived@example.test", java.time.Duration.ofSeconds(1))).isTrue();

        clock.advance(java.time.Duration.ofSeconds(2));
        // Refresh the expired entry before the capacity check. The next cleanup
        // deadline is intentionally stale, but no entry is expired anymore.
        assertThat(throttle.allow("short-lived@example.test", java.time.Duration.ofHours(1))).isTrue();
        assertThat(throttle.cleanupSweepCount()).isZero();

        for (int i = 0; i < 100; i++) {
            assertThat(throttle.allow("rejected-" + i + "@example.test", java.time.Duration.ofHours(1))).isFalse();
        }
        assertThat(throttle.cleanupSweepCount()).isEqualTo(1);
    }

    @Test
    void mailFailureIsContainedAndRecordedAsDeliveryFailure() {
        PasswordResetDelivery delivery = mock(PasswordResetDelivery.class);
        PasswordResetDeliveryStatusService status = mock(PasswordResetDeliveryStatusService.class);
        doThrow(new IllegalStateException("SMTP down")).when(delivery).deliver("user@example.test", "token");
        PasswordResetDeliveryDispatcher dispatcher = new PasswordResetDeliveryDispatcher(delivery, status);

        assertThat(dispatcher.dispatch("token-id", "user-id", "user@example.test", "token")).isTrue();
        verify(status, timeout(2_000)).markFailed("token-id", "user-id");
        dispatcher.shutdown();
    }

    @Test
    void statusUpdateFailureDoesNotMakeAAlreadySentTokenUndeliverable() {
        PasswordResetDelivery delivery = mock(PasswordResetDelivery.class);
        PasswordResetDeliveryStatusService status = mock(PasswordResetDeliveryStatusService.class);
        doThrow(new IllegalStateException("database unavailable")).when(status).markSent("token-id", "user-id");
        PasswordResetDeliveryDispatcher dispatcher = new PasswordResetDeliveryDispatcher(delivery, status);

        assertThat(dispatcher.dispatch("token-id", "user-id", "user@example.test", "token")).isTrue();
        verify(status, timeout(2_000)).markSent("token-id", "user-id");
        verify(status, timeout(2_000)).markFailed("token-id", "user-id");
        dispatcher.shutdown();
    }

    private static final class CapturingGateway implements MailSenderGateway {
        private String to;
        private String subject;
        private String text;

        @Override
        public void send(String from, String to, String subject, String text) {
            this.to = to;
            this.subject = subject;
            this.text = text;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(java.time.Duration duration) {
            current = current.plus(duration);
        }
    }
}
