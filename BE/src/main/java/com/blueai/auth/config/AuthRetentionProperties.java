package com.blueai.auth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Optional operational retention policy for authentication records.
 *
 * <p>The scheduler is deliberately disabled by default. Enabling it requires
 * an explicit deployment property; the policy never deletes users or their
 * domain data.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app.auth.retention")
public class AuthRetentionProperties {

    private boolean scheduledEnabled;

    @Min(30)
    @Max(3650)
    private int retentionDays = 90;

    private Duration interval = Duration.ofHours(24);
    private Duration initialDelay = Duration.ofHours(24);

    public boolean isScheduledEnabled() {
        return scheduledEnabled;
    }

    public void setScheduledEnabled(boolean scheduledEnabled) {
        this.scheduledEnabled = scheduledEnabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Auth retention interval must be positive.");
        }
        this.interval = interval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("Auth retention initial delay must not be negative.");
        }
        this.initialDelay = initialDelay;
    }
}
