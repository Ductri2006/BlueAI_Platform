package com.blueai.ai.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.tasks.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class AiTaskRecoveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AiTaskRecoveryScheduler.class);
    private final AiTaskRecoveryService recovery;

    public AiTaskRecoveryScheduler(AiTaskRecoveryService recovery) {
        this.recovery = recovery;
    }

    @Scheduled(initialDelay = 5000, fixedDelay = 10000)
    public void recoverExpiredTasks() {
        int recovered = recovery.recoverExpiredTasks();
        if (recovered > 0) {
            LOG.warn("Expired {} AI tasks whose results were not committed; no provider requests were retried.", recovered);
        }
    }
}
