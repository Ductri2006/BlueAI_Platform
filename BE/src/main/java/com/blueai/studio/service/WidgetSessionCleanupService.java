package com.blueai.studio.service;

import com.blueai.studio.repository.BotSessionRepository;
import com.blueai.studio.repository.BotTurnRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Removes expired public-widget sessions and their idempotency records.
 *
 * <p>Turns must be deleted first because they reference the session. Keeping
 * this cleanup server-side prevents an attacker from filling the database by
 * repeatedly creating short-lived public sessions.</p>
 */
@Service
public class WidgetSessionCleanupService {

    private final BotSessionRepository sessionRepository;
    private final BotTurnRepository turnRepository;

    public WidgetSessionCleanupService(
            BotSessionRepository sessionRepository,
            BotTurnRepository turnRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.turnRepository = turnRepository;
    }

    @Scheduled(
            fixedDelayString = "${app.widget.cleanup-interval:15m}",
            initialDelayString = "${app.widget.cleanup-interval:15m}"
    )
    @Transactional
    public void deleteExpiredSessions() {
        Instant cutoff = Instant.now();
        turnRepository.deleteExpiredBefore(cutoff);
        sessionRepository.deleteExpiredBefore(cutoff);
    }
}
