package com.blueai.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runnable used by the optional scheduler configuration. It has no user-data
 * deletion path: the retention service only removes expired/revoked auth
 * records and audit entries older than the configured policy.
 */
public final class AuthRecordRetentionScheduler implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthRecordRetentionScheduler.class);

    private final AuthRecordRetentionService retentionService;
    private final int retentionDays;

    public AuthRecordRetentionScheduler(AuthRecordRetentionService retentionService, int retentionDays) {
        this.retentionService = retentionService;
        this.retentionDays = retentionDays;
    }

    @Override
    public void run() {
        try {
            retentionService.purge(retentionDays, null);
        } catch (RuntimeException exception) {
            // A failed transaction rolls back as a unit; the next interval can retry.
            // Do not include user identifiers, tokens, or mail data in the log message.
            LOGGER.error("Scheduled auth-record retention purge failed; will retry", exception);
        }
    }
}
