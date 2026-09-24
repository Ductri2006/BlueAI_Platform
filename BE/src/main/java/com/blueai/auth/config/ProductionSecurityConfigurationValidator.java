package com.blueai.auth.config;

import com.blueai.studio.config.WidgetProtectionProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fails startup under an explicit {@code prod} or {@code production} profile
 * when a deployment would silently fall back to development credentials or
 * unsafe public-widget settings. It reports property names only and never
 * prints secret values.
 */
@Component
@Profile({"prod", "production"})
public class ProductionSecurityConfigurationValidator implements SmartInitializingSingleton {

    private static final String DEVELOPMENT_JWT_SECRET =
            "ZGV2LW9ubHktYmx1ZWFpLWp3dC1zZWNyZXQtbXVzdC1iZS1yZXBsYWNlZCE=";
    private static final String DEVELOPMENT_WIDGET_SALT =
            "local-widget-client-key-salt-change-before-production";
    private static final String DEVELOPMENT_WORKER_API_KEY =
            "local-worker-api-key-change-before-production";
    private static final String DEVELOPMENT_BOOTSTRAP_PASSWORD = "BlueAiAdmin123!";
    private static final Set<String> DEVELOPMENT_DATABASE_PASSWORDS = Set.of(
            "replace-with-db-password",
            "blueai-local",
            "blueai-root-local"
    );

    private final WidgetProtectionProperties widgetProperties;
    private final CorsProperties corsProperties;
    private final JwtProperties jwtProperties;
    private final Environment environment;

    public ProductionSecurityConfigurationValidator(
            WidgetProtectionProperties widgetProperties,
            CorsProperties corsProperties,
            JwtProperties jwtProperties,
            Environment environment
    ) {
        this.widgetProperties = widgetProperties;
        this.corsProperties = corsProperties;
        this.jwtProperties = jwtProperties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validate();
    }

    void validate() {
        List<String> issues = new ArrayList<>();

        if (!widgetProperties.isRedisEnabled()) {
            issues.add("APP_WIDGET_REDIS_ENABLED must be true");
        }
        if (isDevelopmentOrTooShort(widgetProperties.getClientKeySalt(), DEVELOPMENT_WIDGET_SALT)) {
            issues.add("APP_WIDGET_CLIENT_KEY_SALT must be a new random value of at least 32 characters");
        }
        if (isBlank(jwtProperties.getSecret()) || DEVELOPMENT_JWT_SECRET.equals(jwtProperties.getSecret())) {
            issues.add("APP_JWT_SECRET must not use the development default");
        }
        if (isDevelopmentOrTooShort(environment.getProperty("app.worker.api-key"), DEVELOPMENT_WORKER_API_KEY)) {
            issues.add("APP_WORKER_API_KEY must be a new random value of at least 32 characters");
        }
        if (isDevelopmentDatabasePassword(environment.getProperty("spring.datasource.password"))) {
            issues.add("DB_PASSWORD must not be blank or use a development default");
        }

        if (!hasOnlySecureExplicitOrigins(corsProperties.getAllowedOrigins())) {
            issues.add("APP_CORS_ALLOWED_ORIGINS must contain one or more explicit HTTPS origins without localhost or wildcard");
        }
        if (!hasOnlySecureExplicitOrigins(corsProperties.getWidgetAllowedOrigins())) {
            issues.add("APP_WIDGET_ALLOWED_ORIGINS must contain one or more explicit HTTPS origins without wildcard");
        }

        if (environment.getProperty("app.auth.public-registration-enabled", Boolean.class, false)) {
            issues.add("APP_PUBLIC_REGISTRATION_ENABLED must be false");
        }
        if (environment.getProperty("app.rag.allow-shared-demo-knowledge", Boolean.class, false)) {
            issues.add("APP_RAG_ALLOW_SHARED_DEMO_KNOWLEDGE must be false");
        }
        validateBootstrapAdmin(issues);
        validatePasswordResetMail(issues);
        validateForwardedHeaders(issues);
        validateWidgetLimits(issues);

        if (!issues.isEmpty()) {
            throw new IllegalStateException(
                    "Unsafe production configuration: " + String.join("; ", issues)
                            + ". Review BE/PRODUCTION_DEPLOYMENT.md before starting the service."
            );
        }
    }

    private void validateBootstrapAdmin(List<String> issues) {
        String email = environment.getProperty("app.bootstrap-admin.email", "");
        String password = environment.getProperty("app.bootstrap-admin.password", "");
        if (isBlank(email) != isBlank(password)) {
            issues.add("APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD must both be set or both be blank");
        }
        if (DEVELOPMENT_BOOTSTRAP_PASSWORD.equals(password)) {
            issues.add("APP_BOOTSTRAP_ADMIN_PASSWORD must not use the development default");
        }
        if (!isBlank(email) && "admin@blueai.local".equalsIgnoreCase(email.trim())) {
            issues.add("APP_BOOTSTRAP_ADMIN_EMAIL must not use the development default");
        }
    }

    private void validatePasswordResetMail(List<String> issues) {
        if (!environment.getProperty("app.auth.password-reset.enabled", Boolean.class, false)) {
            issues.add("APP_RESET_ENABLED must be true in production");
        }
        if (isBlank(environment.getProperty("spring.mail.host"))) {
            issues.add("SMTP_HOST is required when password reset is enabled");
        }
        if (isBlank(environment.getProperty("app.auth.password-reset.mail-from"))) {
            issues.add("APP_RESET_MAIL_FROM is required when password reset is enabled");
        }
        String frontendUrl = environment.getProperty("app.auth.password-reset.frontend-url");
        if (!isSecurePasswordResetUrl(frontendUrl)) {
            issues.add("APP_RESET_FRONTEND_URL must be an absolute HTTPS URL with a valid host and without user info or fragment");
        }
    }

    private boolean isSecurePasswordResetUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = URI.create(value.trim());
            int port = uri.getPort();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.isAbsolute()
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getRawFragment() == null
                    && (port == -1 || (port >= 1 && port <= 65_535));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void validateForwardedHeaders(List<String> issues) {
        if (!widgetProperties.isTrustForwardedHeaders()) {
            return;
        }
        List<String> trustedProxyCidrs = widgetProperties.getTrustedProxyCidrs();
        if (trustedProxyCidrs == null || trustedProxyCidrs.isEmpty()) {
            issues.add("APP_WIDGET_TRUSTED_PROXY_CIDRS is required when APP_WIDGET_TRUST_FORWARDED_HEADERS=true");
            return;
        }
        if (trustedProxyCidrs.stream().anyMatch(cidr -> !isSafeCidr(cidr))) {
            issues.add("APP_WIDGET_TRUSTED_PROXY_CIDRS must contain valid, non-wildcard IP CIDRs");
        }
    }

    private void validateWidgetLimits(List<String> issues) {
        if (widgetProperties.getMaxSessionCreationsPerWindow() < 1
                || widgetProperties.getMaxMessagesPerWindowPerClient() < 1
                || widgetProperties.getMaxMessagesPerWindowPerSession() < 1
                || widgetProperties.getMaxMessagesPerSession() < 1
                || widgetProperties.getDuplicateReplayLimit() < 0) {
            issues.add("widget rate and replay limits must be non-negative, with all rate limits at least one");
        }
        if (!isPositive(widgetProperties.getSessionTtl())
                || !isPositive(widgetProperties.getRateLimitWindow())
                || !isPositive(widgetProperties.getDuplicateWindow())
                || !isPositive(widgetProperties.getRequestLockTtl())) {
            issues.add("widget TTL and time-window values must be positive");
        }
    }

    private boolean hasOnlySecureExplicitOrigins(List<String> origins) {
        return origins != null && !origins.isEmpty() && origins.stream().allMatch(this::isSecureExplicitOrigin);
    }

    private boolean isSecureExplicitOrigin(String value) {
        if (isBlank(value) || value.contains("*")) {
            return false;
        }
        try {
            URI origin = URI.create(value.trim());
            String host = origin.getHost();
            return "https".equalsIgnoreCase(origin.getScheme())
                    && host != null
                    && !isLocalHost(host)
                    && origin.getUserInfo() == null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isLocalHost(String host) {
        String normalized = host.trim().toLowerCase();
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || normalized.startsWith("127.")
                || normalized.endsWith(".local");
    }

    private boolean isSafeCidr(String cidr) {
        if (isBlank(cidr)) {
            return false;
        }
        String[] parts = cidr.trim().split("/", -1);
        if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F:.]+")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(parts[0]);
            int prefixLength = Integer.parseInt(parts[1]);
            return prefixLength > 0 && prefixLength <= address.getAddress().length * Byte.SIZE;
        } catch (UnknownHostException | NumberFormatException exception) {
            return false;
        }
    }

    private boolean isDevelopmentOrTooShort(String value, String developmentDefault) {
        return isBlank(value) || developmentDefault.equals(value) || value.trim().length() < 32;
    }

    private boolean isDevelopmentDatabasePassword(String value) {
        return isBlank(value) || DEVELOPMENT_DATABASE_PASSWORDS.contains(value);
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
