package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetSessionRequestCoordinatorTest {

    @Test
    void allowsOnlyOneInFlightRequestPerLocalSession() {
        WidgetSessionRequestCoordinator coordinator = new WidgetSessionRequestCoordinator(
                null,
                new WidgetProtectionProperties()
        );

        try (WidgetSessionRequestCoordinator.LockHandle first = coordinator.tryAcquire("session-1")) {
            assertThat(first.acquired()).isTrue();
            try (WidgetSessionRequestCoordinator.LockHandle second = coordinator.tryAcquire("session-1")) {
                assertThat(second.acquired()).isFalse();
            }
        }

        try (WidgetSessionRequestCoordinator.LockHandle afterRelease = coordinator.tryAcquire("session-1")) {
            assertThat(afterRelease.acquired()).isTrue();
        }
    }
}
