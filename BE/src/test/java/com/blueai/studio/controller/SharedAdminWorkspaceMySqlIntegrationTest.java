package com.blueai.studio.controller;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "MYSQL_SHARED_ADMIN_TEST_URL", matches = ".+")
class SharedAdminWorkspaceMySqlIntegrationTest extends SharedAdminWorkspaceIntegrationTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        String url = System.getenv("MYSQL_SHARED_ADMIN_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/blueai_shared_admin_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Shared admin tests require a disposable blueai_shared_admin_test database.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> System.getenv("MYSQL_SHARED_ADMIN_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("MYSQL_SHARED_ADMIN_TEST_PASSWORD"));
        registry.add("spring.flyway.placeholders.text_type", () -> "LONGTEXT");
        registry.add("spring.flyway.placeholders.long_text_type", () -> "LONGTEXT");
        registry.add("spring.flyway.placeholders.idempotency_key_type",
                () -> "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    }
}
