package com.blueai.product;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.products")
public class ProductProperties {
    private String baseUrl = "https://api.sandbox.bluesim.com.vn/api/open/v1";
    private String apiKey = "";
    private Duration timeout = Duration.ofSeconds(30);
    private Duration maxAge = Duration.ofMinutes(30);
    private String sourceEnvironment = "AUTO";
    private boolean productionApproved;
    private boolean requireVerifiedCatalog;
    private boolean publicAdviceEnabled;
    private boolean sellingPriceCurrencyConfirmed;
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    public Duration getMaxAge() { return maxAge; }
    public void setMaxAge(Duration value) { maxAge = value; }
    public String getSourceEnvironment() { return sourceEnvironment; }
    public void setSourceEnvironment(String value) { sourceEnvironment = value; }
    public boolean isRequireVerifiedCatalog() { return requireVerifiedCatalog; }
    public void setRequireVerifiedCatalog(boolean value) { requireVerifiedCatalog = value; }
    public boolean isProductionApproved() { return productionApproved; }
    public void setProductionApproved(boolean value) { productionApproved = value; }
    public boolean isPublicAdviceEnabled() { return publicAdviceEnabled; }
    public void setPublicAdviceEnabled(boolean value) { publicAdviceEnabled = value; }
    public boolean isSellingPriceCurrencyConfirmed() { return sellingPriceCurrencyConfirmed; }
    public void setSellingPriceCurrencyConfirmed(boolean value) { sellingPriceCurrencyConfirmed = value; }

    /** Credentials are sent only to this configured HTTPS origin, never to a product-supplied URL. */
    public String sourceBaseUrl() {
        try {
            var uri = java.net.URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                return null;
            return uri.normalize().toString().replaceAll("/+$", "");
        } catch (RuntimeException invalid) { return null; }
    }

    public String sourceEnvironment() {
        String url = sourceBaseUrl();
        if (url == null) return "UNKNOWN";
        String host = java.net.URI.create(url).getHost().toLowerCase(java.util.Locale.ROOT);
        boolean sandbox = java.util.Arrays.asList(host.split("\\.")).contains("sandbox");
        String configured = sourceEnvironment == null ? "" : sourceEnvironment.toUpperCase(java.util.Locale.ROOT);
        if (sandbox) return "PRODUCTION".equals(configured) ? "UNKNOWN" : "SANDBOX";
        return "PRODUCTION".equals(configured) ? "PRODUCTION" : "UNKNOWN";
    }

    public boolean adviceConfigured() {
        String environment = sourceEnvironment();
        return configured() && sourceBaseUrl() != null && maxAge != null && !maxAge.isNegative() && !maxAge.isZero()
                && ("SANDBOX".equals(environment) || ("PRODUCTION".equals(environment) && productionApproved));
    }
}
