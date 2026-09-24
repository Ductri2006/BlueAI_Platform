package com.blueai.auth.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends reset mail after commit without making the request thread wait on SMTP.
 * The bounded queue prevents SMTP outages from consuming unbounded memory.
 */
@Component
public class PasswordResetDeliveryDispatcher {

    private final PasswordResetDelivery delivery;
    private final PasswordResetDeliveryStatusService statusService;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1_000),
            new ThreadPoolExecutor.AbortPolicy()
    );

    public PasswordResetDeliveryDispatcher(
            PasswordResetDelivery delivery,
            PasswordResetDeliveryStatusService statusService
    ) {
        this.delivery = delivery;
        this.statusService = statusService;
    }

    public boolean dispatch(String tokenId, String userId, String email, String rawToken) {
        try {
            executor.execute(() -> {
                try {
                    delivery.deliver(email, rawToken);
                    statusService.markSent(tokenId, userId);
                } catch (RuntimeException exception) {
                    statusService.markFailed(tokenId, userId);
                }
            });
            return true;
        } catch (RuntimeException exception) {
            statusService.markFailed(tokenId, userId);
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
