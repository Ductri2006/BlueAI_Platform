package com.blueai.auth.config;

import com.blueai.studio.config.WidgetProtectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSecurityConfigurationValidatorTest {

    @Test
    void rejectsDevelopmentDefaultsAndUnsafePublicWidgetSettings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.worker.api-key", "local-worker-api-key-change-before-production")
                .withProperty("app.auth.public-registration-enabled", "true")
                .withProperty("app.rag.allow-shared-demo-knowledge", "true");
        JwtProperties jwtProperties = new JwtProperties();
        CorsProperties corsProperties = new CorsProperties();
        WidgetProtectionProperties widgetProperties = new WidgetProtectionProperties();

        assertThatThrownBy(() -> validator(widgetProperties, corsProperties, jwtProperties, environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_WIDGET_REDIS_ENABLED")
                .hasMessageContaining("APP_JWT_SECRET")
                .hasMessageContaining("APP_WORKER_API_KEY")
                .hasMessageContaining("DB_PASSWORD")
                .hasMessageContaining("APP_CORS_ALLOWED_ORIGINS")
                .hasMessageContaining("APP_WIDGET_ALLOWED_ORIGINS")
                .hasMessageContaining("APP_PUBLIC_REGISTRATION_ENABLED")
                .hasMessageContaining("APP_RAG_ALLOW_SHARED_DEMO_KNOWLEDGE");
    }

    @Test
    void acceptsACompleteExplicitProductionConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.worker.api-key", "worker-" + "x".repeat(48))
                .withProperty("spring.datasource.password", "database-" + "x".repeat(48))
                .withProperty("app.auth.public-registration-enabled", "false")
                .withProperty("app.rag.allow-shared-demo-knowledge", "false")
                .withProperty("app.bootstrap-admin.email", "ops@example.test")
                .withProperty("app.bootstrap-admin.password", "admin-" + "x".repeat(48));
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(Base64.getEncoder().encodeToString(new byte[48]));
        CorsProperties corsProperties = new CorsProperties();
        corsProperties.setAllowedOrigins(List.of("https://app.example.test"));
        corsProperties.setWidgetAllowedOrigins(List.of("https://widget.example.test"));
        WidgetProtectionProperties widgetProperties = new WidgetProtectionProperties();
        widgetProperties.setRedisEnabled(true);
        widgetProperties.setClientKeySalt("salt-" + "x".repeat(48));
        widgetProperties.setTrustForwardedHeaders(true);
        widgetProperties.setTrustedProxyCidrs(List.of("172.30.0.10/32"));

        assertThatCode(() -> validator(widgetProperties, corsProperties, jwtProperties, environment).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsProductionWithoutSmtpOrPasswordResetConfiguration() {
        MockEnvironment environment = safeEnvironment();
        assertThatCode(() -> validator(
                safeWidgetProperties(), safeCorsProperties(), safeJwtProperties(), environment
        ).validate()).doesNotThrowAnyException();
    }

    @Test
    void missingDatabasePasswordCannotFallBackToAHardcodedCredential() throws Exception {
        var defaults = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        MockEnvironment environment = safeEnvironment();
        environment.setProperty("spring.datasource.password",
                new MockEnvironment().resolvePlaceholders(defaults.getProperty("spring.datasource.password")));
        assertThatThrownBy(() -> validator(
                safeWidgetProperties(), safeCorsProperties(), safeJwtProperties(), environment
        ).validate()).isInstanceOf(IllegalStateException.class).hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void rejectsForwardedHeaderTrustWithoutAProxyAllowlist() {
        WidgetProtectionProperties widgetProperties = safeWidgetProperties();
        widgetProperties.setTrustForwardedHeaders(true);

        assertThatThrownBy(() -> validator(
                widgetProperties,
                safeCorsProperties(),
                safeJwtProperties(),
                safeEnvironment()
        ).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_WIDGET_TRUSTED_PROXY_CIDRS");
    }

    @Test
    void rejectsAProxyAllowlistThatTrustsEveryRemoteAddress() {
        WidgetProtectionProperties widgetProperties = safeWidgetProperties();
        widgetProperties.setTrustForwardedHeaders(true);
        widgetProperties.setTrustedProxyCidrs(List.of("0.0.0.0/0"));

        assertThatThrownBy(() -> validator(
                widgetProperties,
                safeCorsProperties(),
                safeJwtProperties(),
                safeEnvironment()
        ).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_WIDGET_TRUSTED_PROXY_CIDRS");
    }

    private ProductionSecurityConfigurationValidator validator(
            WidgetProtectionProperties widgetProperties,
            CorsProperties corsProperties,
            JwtProperties jwtProperties,
            MockEnvironment environment
    ) {
        return new ProductionSecurityConfigurationValidator(widgetProperties, corsProperties, jwtProperties, environment);
    }

    private WidgetProtectionProperties safeWidgetProperties() {
        WidgetProtectionProperties properties = new WidgetProtectionProperties();
        properties.setRedisEnabled(true);
        properties.setClientKeySalt("salt-" + "x".repeat(48));
        return properties;
    }

    private CorsProperties safeCorsProperties() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("https://app.example.test"));
        properties.setWidgetAllowedOrigins(List.of("https://widget.example.test"));
        return properties;
    }

    private JwtProperties safeJwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(Base64.getEncoder().encodeToString(new byte[48]));
        return properties;
    }

    private MockEnvironment safeEnvironment() {
        return new MockEnvironment()
                .withProperty("app.worker.api-key", "worker-" + "x".repeat(48))
                .withProperty("spring.datasource.password", "database-" + "x".repeat(48))
                .withProperty("app.auth.public-registration-enabled", "false")
                .withProperty("app.rag.allow-shared-demo-knowledge", "false")
                .withProperty("app.bootstrap-admin.email", "")
                .withProperty("app.bootstrap-admin.password", "");
    }
}
