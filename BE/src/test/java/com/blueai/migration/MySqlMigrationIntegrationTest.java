package com.blueai.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL gate, enabled only for an empty disposable CI database. */
@EnabledIfEnvironmentVariable(named = "MYSQL_MIGRATION_TEST_URL", matches = ".+")
class MySqlMigrationIntegrationTest {

    @Test
    void appliesFreshSchemaAndUpgradesV5ToV6WithoutLosingExistingData() throws Exception {
        String url = System.getenv("MYSQL_MIGRATION_TEST_URL");
        String username = System.getenv("MYSQL_MIGRATION_TEST_USERNAME");
        String password = System.getenv("MYSQL_MIGRATION_TEST_PASSWORD");
        // Fail closed before any DDL if pointed at another database.
        assertThat(url).matches("jdbc:mysql://[^/]+/blueai_migration_test(?:\\?.*)?");
        assertThat(username).isNotBlank();
        assertThat(password).isNotBlank();
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var tables = statement.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()")) {
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).as("migration gate requires an empty disposable database").isZero();
        }

        Flyway v5 = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT"))
                .target(MigrationVersion.fromVersion("5"))
                .cleanDisabled(true)
                .load();
        assertThat(v5.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(v5.info().current().getVersion().getVersion()).isEqualTo("5");
        String userId = UUID.randomUUID().toString();
        try (var connection = DriverManager.getConnection(url, username, password);
             var insert = connection.prepareStatement(
                     "INSERT INTO app_users (id,email,password_hash,full_name,role,status,created_at,updated_at) "
                             + "VALUES (?,?,?,?,?,?,?,?)")) {
            insert.setString(1, userId);
            insert.setString(2, "migration-probe@example.test");
            insert.setString(3, "not-a-real-password-hash");
            insert.setString(4, "Migration preservation probe");
            insert.setString(5, "USER");
            insert.setString(6, "ACTIVE");
            insert.setTimestamp(7, Timestamp.from(Instant.now()));
            insert.setTimestamp(8, Timestamp.from(Instant.now()));
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }

        Flyway latest = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT"))
                .cleanDisabled(true)
                .load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("6");
        latest.validate();
        assertThat(latest.info().pending()).isEmpty();
        assertThat(latest.migrate().migrationsExecuted).isZero();

        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            try (var users = statement.executeQuery("SELECT id,auth_version FROM app_users")) {
                assertThat(users.next()).isTrue();
                assertThat(users.getString("id")).isEqualTo(userId);
                assertThat(users.getLong("auth_version")).isZero();
                assertThat(users.next()).isFalse();
            }
            String usageId = UUID.randomUUID().toString();
            try (var insert = connection.prepareStatement(
                    "INSERT INTO gemini_usage_events (id,model,prompt_tokens,completion_tokens,total_tokens,recorded_at) "
                            + "VALUES (?, 'migration-test', 11, 7, 18, CURRENT_TIMESTAMP(6))")) {
                insert.setString(1, usageId);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            try (var usage = statement.executeQuery("SELECT prompt_tokens,completion_tokens,total_tokens FROM gemini_usage_events")) {
                assertThat(usage.next()).isTrue();
                assertThat(usage.getLong("prompt_tokens")).isEqualTo(11);
                assertThat(usage.getLong("completion_tokens")).isEqualTo(7);
                assertThat(usage.getLong("total_tokens")).isEqualTo(18);
            }
            try (var index = statement.executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'gemini_usage_events' AND INDEX_NAME = 'idx_gemini_usage_recorded_at' "
                            + "AND COLUMN_NAME = 'recorded_at'")) {
                assertThat(index.next()).isTrue();
                assertThat(index.getInt(1)).isEqualTo(1);
            }
        }
    }
}
