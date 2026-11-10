package com.blueai.auth.controller;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercises the HTTP recovery contract with the deployment database engine. */
@EnabledIfEnvironmentVariable(named = "MYSQL_AUTH_TEST_URL", matches = ".+")
class MySqlPasswordRecoveryIntegrationTest extends PasswordResetIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        String url = System.getenv("MYSQL_AUTH_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/blueai_auth_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Recovery tests require the disposable blueai_auth_test database.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> System.getenv("MYSQL_AUTH_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("MYSQL_AUTH_TEST_PASSWORD"));
        registry.add("spring.flyway.placeholders.text_type", () -> "LONGTEXT");
        registry.add("spring.flyway.placeholders.long_text_type", () -> "LONGTEXT");
        registry.add("spring.flyway.placeholders.idempotency_key_type", () -> "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    }
}
