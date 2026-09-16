package com.blueai.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://localhost:3000",
            "http://localhost:5173"
    ));

    /**
     * Origins allowed to embed the public widget. The widget API is public and
     * does not use browser credentials, so a wildcard is safe at the CORS layer;
     * abuse protection is enforced independently by the runtime rate limiter.
     * Production deployments can replace this with an explicit comma-separated
     * allowlist via APP_WIDGET_ALLOWED_ORIGINS.
     */
    private List<String> widgetAllowedOrigins = new ArrayList<>(List.of("*"));

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public List<String> getWidgetAllowedOrigins() {
        return widgetAllowedOrigins;
    }

    public void setWidgetAllowedOrigins(List<String> widgetAllowedOrigins) {
        this.widgetAllowedOrigins = widgetAllowedOrigins;
    }
}
