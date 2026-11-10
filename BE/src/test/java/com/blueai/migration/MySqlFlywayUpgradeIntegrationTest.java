package com.blueai.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Two separate Maven runs prove an engine upgrade, not just two instances of the new engine. */
@EnabledIfEnvironmentVariable(named = "MYSQL_FLYWAY_UPGRADE_PHASE", matches = "seed|verify")
@ExtendWith(OutputCaptureExtension.class)
class MySqlFlywayUpgradeIntegrationTest {

    @Test
    void preservesOldEngineHistoryAndDataOnMySql84(CapturedOutput output) throws Exception {
        String url = System.getenv("MYSQL_FLYWAY_UPGRADE_URL");
        String username = System.getenv("MYSQL_FLYWAY_UPGRADE_USERNAME");
        String password = System.getenv("MYSQL_FLYWAY_UPGRADE_PASSWORD");
        // This test deliberately persists state between runs; never use an application database.
        assertThat(url).matches("jdbc:mysql://[^/]+/blueai_flyway_upgrade_test(?:\\?.*)?");
        assertThat(username).isNotBlank();
        assertThat(password).isNotBlank();
        boolean seed = "seed".equals(System.getenv("MYSQL_FLYWAY_UPGRADE_PHASE"));
        String expectedEngine = seed ? "11.7.2" : "11.20.3";
        assertThat(Flyway.class.getProtectionDomain().getCodeSource().getLocation().getPath())
                .as("the seed must run the old engine, verification must run the upgraded engine")
                .endsWith("flyway-core-" + expectedEngine + ".jar");

        try (var connection = DriverManager.getConnection(url, username, password)) {
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(connection.getMetaData().getDatabaseMinorVersion()).isEqualTo(4);
            if (seed) {
                assertThat(count(connection, "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE()"))
                        .as("seed requires an empty disposable database").isZero();
            }
            var configuration = Flyway.configure().dataSource(url, username, password)
                    .locations("classpath:db/migration")
                    .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT",
                            "idempotency_key_type", "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                    .cleanDisabled(true);
            if (seed) configuration.target(MigrationVersion.fromVersion("6"));
            Flyway flyway = configuration.load();
            if (seed) {
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
                seedPreservationProbes(connection);
            } else {
                assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
                // migrate validates applied checksums while allowing pending migrations.
                // A standalone validate before migrate rejects pending migrations.
                assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
                flyway.validate();
                assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("14");
                assertThat(flyway.info().pending()).isEmpty();
                assertThat(flyway.migrate().migrationsExecuted).isZero();
                verifyPreservationProbes(connection);
                assertThat(count(connection, "SELECT COUNT(*) FROM app_users WHERE active_session_id IS NULL"))
                        .as("existing users have no active login session after V10").isEqualTo(1);
                assertThat(output.getAll()).doesNotContain("Flyway upgrade recommended");
            }
        }
    }

    private void seedPreservationProbes(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE upgrade_history_probe AS SELECT * FROM flyway_schema_history");
            statement.executeUpdate("""
                    INSERT INTO app_users (id,email,password_hash,full_name,role,status,created_at,updated_at)
                    VALUES ('00000000-0000-0000-0000-000000000001','upgrade@example.test',
                            'not-a-real-password-hash','Upgrade preservation probe','USER','ACTIVE',
                            CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_integration_clients (id,owner_id,name,credential_hash,created_at)
                    VALUES ('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',
                            'Upgrade probe',REPEAT('0',64),CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_task_ledger
                      (id,client_id,idempotency_key,request_digest,capability,status,response_json,created_at,completed_at)
                    VALUES ('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000002',
                            'upgrade:completed',REPEAT('0',64),'CLASSIFY','COMPLETED','{"probe":"preserve"}',
                            CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)),
                           ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000002',
                            'upgrade:abandoned',REPEAT('1',64),'CLASSIFY','PROCESSING',NULL,
                            CURRENT_TIMESTAMP(6),NULL)
                    """);
        }
    }

    private void verifyPreservationProbes(Connection connection) throws Exception {
        assertThat(count(connection, "SELECT COUNT(*) FROM upgrade_history_probe")).isEqualTo(6);
        assertThat(count(connection, """
                SELECT COUNT(*) FROM upgrade_history_probe p JOIN flyway_schema_history h
                  ON h.installed_rank = p.installed_rank AND h.version = p.version
                 AND h.description = p.description AND h.type = p.type AND h.script = p.script
                 AND h.checksum = p.checksum AND h.installed_by = p.installed_by
                 AND h.installed_on = p.installed_on AND h.execution_time = p.execution_time
                 AND h.success = p.success
                """)).as("all old history/checksums must remain unchanged").isEqualTo(6);
        assertThat(count(connection, "SELECT COUNT(*) FROM app_users WHERE email='upgrade@example.test' "
                + "AND password_hash='not-a-real-password-hash' AND auth_version=0")).isEqualTo(1);
        assertThat(count(connection, "SELECT COUNT(*) FROM ai_task_ledger "
                + "WHERE expires_at=created_at")).isEqualTo(2);
        assertThat(count(connection, "SELECT COUNT(*) FROM ai_task_ledger "
                + "WHERE idempotency_key='upgrade:completed' AND status='COMPLETED' "
                + "AND response_json='{\"probe\":\"preserve\"}' AND completed_at IS NOT NULL")).isEqualTo(1);
        assertThat(count(connection, "SELECT COUNT(*) FROM ai_task_ledger "
                + "WHERE idempotency_key='upgrade:abandoned' AND status='PROCESSING' "
                + "AND response_json IS NULL AND completed_at IS NULL")).isEqualTo(1);
    }

    private int count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }
}
