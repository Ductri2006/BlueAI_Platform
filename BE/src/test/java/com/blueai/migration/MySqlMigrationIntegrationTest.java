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
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT",
                        "idempotency_key_type", "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                .target(MigrationVersion.fromVersion("5"))
                .cleanDisabled(true)
                .load();
        assertThat(v5.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(v5.info().current().getVersion().getVersion()).isEqualTo("5");
        String userId = UUID.randomUUID().toString();
        String clientId = UUID.randomUUID().toString();
        String legacyTaskId = UUID.randomUUID().toString();
        Instant legacyCreatedAt = Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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

        try (var connection = DriverManager.getConnection(url, username, password)) {
            try (var bot = connection.prepareStatement("""
                    INSERT INTO studio_bots (id,owner_id,name,locale,timezone,status,public_key,draft_revision,created_at,updated_at,row_version)
                    VALUES ('00000000-0000-0000-0000-000000000020',?,'Legacy Dialog','vi-VN','Asia/Ho_Chi_Minh',
                            'DRAFT','00000000-0000-0000-0000-000000000021',0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),0)
                    """)) {
                bot.setString(1, userId);
                assertThat(bot.executeUpdate()).isEqualTo(1);
            }
            try (var node = connection.createStatement()) {
                assertThat(node.executeUpdate("""
                        INSERT INTO studio_bot_dialog_nodes (id,bot_id,node_name,node_type,response_text,priority)
                        VALUES ('00000000-0000-0000-0000-000000000022','00000000-0000-0000-0000-000000000020',
                                'Legacy welcome','WELCOME','Nội dung cũ cần bảo toàn',0)
                        """)).isEqualTo(1);
            }
            try (var client = connection.prepareStatement("""
                    INSERT INTO ai_integration_clients (id,owner_id,name,credential_hash,created_at)
                    VALUES (?,?,'Legacy migration client',?,?)
                    """)) {
                client.setString(1, clientId);
                client.setString(2, userId);
                client.setString(3, "0".repeat(64));
                client.setTimestamp(4, Timestamp.from(legacyCreatedAt));
                assertThat(client.executeUpdate()).isEqualTo(1);
            }
            try (var task = connection.prepareStatement("""
                    INSERT INTO ai_task_ledger (id,client_id,idempotency_key,request_digest,capability,status,created_at)
                    VALUES (?,?,'legacy:crashed',?,'CLASSIFY','PROCESSING',?)
                    """)) {
                task.setString(1, legacyTaskId);
                task.setString(2, clientId);
                task.setString(3, "0".repeat(64));
                task.setTimestamp(4, Timestamp.from(legacyCreatedAt));
                assertThat(task.executeUpdate()).isEqualTo(1);
            }
        }

        Flyway v6 = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT",
                        "idempotency_key_type", "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                .target(MigrationVersion.fromVersion("6")).cleanDisabled(true).load();
        assertThat(v6.migrate().migrationsExecuted).isEqualTo(1);
        v6.validate();

        Flyway latest = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT",
                        "idempotency_key_type", "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                .cleanDisabled(true)
                .load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(11);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("17");
        latest.validate();
        assertThat(latest.info().pending()).isEmpty();
        assertThat(latest.migrate().migrationsExecuted).isZero();

        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            ProductSchemaAssertions.verify(connection);
            try (var node = statement.executeQuery("""
                    SELECT n.response_text,n.context_reuse_policy,b.dialog_enabled,b.widget_document_ids
                    FROM studio_bot_dialog_nodes n JOIN studio_bots b ON b.id=n.bot_id
                    """)) {
                assertThat(node.next()).isTrue();
                assertThat(node.getString("response_text")).isEqualTo("Nội dung cũ cần bảo toàn");
                assertThat(node.getString("context_reuse_policy")).isEqualTo("NONE");
                assertThat(node.getBoolean("dialog_enabled")).isTrue();
                assertThat(node.wasNull()).isFalse();
                assertThat(node.getString("widget_document_ids")).isNull();
                assertThat(node.next()).isFalse();
            }
            try (var task = connection.prepareStatement("SELECT status,idempotency_key,created_at,expires_at FROM ai_task_ledger WHERE id=?")) {
                task.setString(1, legacyTaskId);
                try (var result = task.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("status")).isEqualTo("PROCESSING");
                    assertThat(result.getString("idempotency_key")).isEqualTo("legacy:crashed");
                    assertThat(result.getTimestamp("expires_at").toInstant()).isEqualTo(legacyCreatedAt);
                    assertThat(result.getTimestamp("created_at")).isEqualTo(result.getTimestamp("expires_at"));
                }
            }
            try (var users = statement.executeQuery("SELECT id,auth_version,active_session_id,password_change_required,temporary_password_expires_at,temporary_password_consumed_at FROM app_users")) {
                assertThat(users.next()).isTrue();
                assertThat(users.getString("id")).isEqualTo(userId);
                assertThat(users.getLong("auth_version")).isZero();
                assertThat(users.getString("active_session_id")).isNull();
                assertThat(users.getBoolean("password_change_required")).isFalse();
                assertThat(users.getTimestamp("temporary_password_expires_at")).isNull();
                assertThat(users.getTimestamp("temporary_password_consumed_at")).isNull();
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
