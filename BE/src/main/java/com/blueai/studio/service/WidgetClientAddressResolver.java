package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * Resolves the caller address without trusting spoofable forwarding headers by
 * default. Deployments may opt in only when a trusted reverse proxy is the
 * sole network path to the backend and replaces X-Forwarded-For itself.
 */
@Service
public class WidgetClientAddressResolver {

    private final WidgetProtectionProperties properties;

    public WidgetClientAddressResolver(WidgetProtectionProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null) {
                String first = forwarded.split(",", 2)[0].trim();
                if (first.length() <= 64 && first.matches("[0-9a-fA-F:.]+")) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
