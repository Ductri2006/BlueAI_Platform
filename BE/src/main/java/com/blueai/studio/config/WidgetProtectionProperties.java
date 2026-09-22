package com.blueai.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for the unauthenticated public-widget boundary.
 *
 * <p>All limits are deliberately externalized so they can be tuned from real
 * traffic data without changing source code. Values here are conservative
 * defaults for an LLM-backed public widget, not a one-size-fits-all policy.</p>
 */
@ConfigurationProperties(prefix = "app.widget.protection")
public class WidgetProtectionProperties {

    private boolean redisEnabled;
    private String redisKeyPrefix = "blueai:widget:";
    private String clientKeySalt = "local-widget-client-key-salt-change-before-production";
    private boolean trustForwardedHeaders;
    private List<String> trustedProxyCidrs = new ArrayList<>();
    private Duration sessionTtl = Duration.ofMinutes(30);
    private Duration rateLimitWindow = Duration.ofMinutes(1);
    private int maxSessionCreationsPerWindow = 10;
    private int maxMessagesPerWindowPerClient = 30;
    private int maxMessagesPerWindowPerSession = 12;
    private int maxMessagesPerSession = 120;
    private int duplicateReplayLimit = 2;
    private Duration duplicateWindow = Duration.ofMinutes(2);
    private Duration requestLockTtl = Duration.ofSeconds(60);

    public boolean isRedisEnabled() { return redisEnabled; }
    public void setRedisEnabled(boolean redisEnabled) { this.redisEnabled = redisEnabled; }

    public String getRedisKeyPrefix() { return redisKeyPrefix; }
    public void setRedisKeyPrefix(String redisKeyPrefix) { this.redisKeyPrefix = redisKeyPrefix; }

    public String getClientKeySalt() { return clientKeySalt; }
    public void setClientKeySalt(String clientKeySalt) { this.clientKeySalt = clientKeySalt; }

    public boolean isTrustForwardedHeaders() { return trustForwardedHeaders; }
    public void setTrustForwardedHeaders(boolean trustForwardedHeaders) { this.trustForwardedHeaders = trustForwardedHeaders; }

    /**
     * CIDR ranges allowed to supply X-Forwarded-For. An empty list is
     * intentionally fail-closed: forwarding headers are ignored even when
     * trustForwardedHeaders was enabled by mistake.
     */
    public List<String> getTrustedProxyCidrs() { return trustedProxyCidrs; }
    public void setTrustedProxyCidrs(List<String> trustedProxyCidrs) {
        this.trustedProxyCidrs = trustedProxyCidrs == null ? new ArrayList<>() : new ArrayList<>(trustedProxyCidrs);
    }

    public Duration getSessionTtl() { return sessionTtl; }
    public void setSessionTtl(Duration sessionTtl) { this.sessionTtl = sessionTtl; }

    public Duration getRateLimitWindow() { return rateLimitWindow; }
    public void setRateLimitWindow(Duration rateLimitWindow) { this.rateLimitWindow = rateLimitWindow; }

    public int getMaxSessionCreationsPerWindow() { return maxSessionCreationsPerWindow; }
    public void setMaxSessionCreationsPerWindow(int value) { this.maxSessionCreationsPerWindow = value; }

    public int getMaxMessagesPerWindowPerClient() { return maxMessagesPerWindowPerClient; }
    public void setMaxMessagesPerWindowPerClient(int value) { this.maxMessagesPerWindowPerClient = value; }

    public int getMaxMessagesPerWindowPerSession() { return maxMessagesPerWindowPerSession; }
    public void setMaxMessagesPerWindowPerSession(int value) { this.maxMessagesPerWindowPerSession = value; }

    public int getMaxMessagesPerSession() { return maxMessagesPerSession; }
    public void setMaxMessagesPerSession(int value) { this.maxMessagesPerSession = value; }

    public int getDuplicateReplayLimit() { return duplicateReplayLimit; }
    public void setDuplicateReplayLimit(int duplicateReplayLimit) { this.duplicateReplayLimit = duplicateReplayLimit; }

    public Duration getDuplicateWindow() { return duplicateWindow; }
    public void setDuplicateWindow(Duration duplicateWindow) { this.duplicateWindow = duplicateWindow; }

    public Duration getRequestLockTtl() { return requestLockTtl; }
    public void setRequestLockTtl(Duration requestLockTtl) { this.requestLockTtl = requestLockTtl; }
}
