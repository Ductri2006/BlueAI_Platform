package com.blueai.quota;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** All limits can be changed without a schema migration. Defaults are a demo allocation. */
@Component
@ConfigurationProperties(prefix = "app.ai-quota")
public class AiQuotaProperties {
    private boolean enabled;
    private long fiveHourTokens = 100_000;
    private long weeklyTokens = 1_000_000;
    private int maxOutputTokens = 2048;
    private String chatModel = "gemini-3.5-flash-lite";
    private String embeddingModel = "gemini-embedding-001";
    private int chatRpm = 15;
    private long chatTpm = 250_000;
    private int chatRpd = 500;
    private int embeddingRpm = 100;
    private long embeddingTpm = 30_000;
    private int embeddingRpd = 1000;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public long getFiveHourTokens() { return fiveHourTokens; }
    public void setFiveHourTokens(long value) { fiveHourTokens = positive(value); }
    public long getWeeklyTokens() { return weeklyTokens; }
    public void setWeeklyTokens(long value) { weeklyTokens = positive(value); }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int value) { maxOutputTokens = (int) Math.min(8192, positive(value)); }
    public String getChatModel() { return chatModel; }
    public void setChatModel(String value) { chatModel = value; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String value) { embeddingModel = value; }
    public int getChatRpm() { return chatRpm; }
    public void setChatRpm(int value) { chatRpm = (int) positive(value); }
    public long getChatTpm() { return chatTpm; }
    public void setChatTpm(long value) { chatTpm = positive(value); }
    public int getChatRpd() { return chatRpd; }
    public void setChatRpd(int value) { chatRpd = (int) positive(value); }
    public int getEmbeddingRpm() { return embeddingRpm; }
    public void setEmbeddingRpm(int value) { embeddingRpm = (int) positive(value); }
    public long getEmbeddingTpm() { return embeddingTpm; }
    public void setEmbeddingTpm(long value) { embeddingTpm = positive(value); }
    public int getEmbeddingRpd() { return embeddingRpd; }
    public void setEmbeddingRpd(int value) { embeddingRpd = (int) positive(value); }
    private static long positive(long value) {
        if (value <= 0) throw new IllegalArgumentException("AI quota limits must be positive");
        return value;
    }
}
