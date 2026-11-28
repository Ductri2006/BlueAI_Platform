package com.blueai.product;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.products")
public class ProductProperties {
    private String baseUrl = "https://api.sandbox.bluesim.com.vn/api/open/v1";
    private String apiKey = "";
    private Duration timeout = Duration.ofSeconds(30);
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
}
