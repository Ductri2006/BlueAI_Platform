package com.blueai.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Direct V1/V3 -> latest upgrades on separate, empty, explicitly named fixture databases. */
@EnabledIfEnvironmentVariable(named = "MYSQL_LEGACY_V1_TEST_URL", matches = ".+")
class MySqlLegacyMigrationIntegrationTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void preservesLegacyRecordsAndChecksumsWhenUpgradingDirectlyToV9(int version) throws Exception {
        String url = System.getenv("MYSQL_LEGACY_V" + version + "_TEST_URL");
        String username = System.getenv("MYSQL_MIGRATION_TEST_USERNAME");
        String password = System.getenv("MYSQL_MIGRATION_TEST_PASSWORD");
        assertThat(url).matches("jdbc:mysql://[^/]+/blueai_legacy_v" + version + "_test(?:\\?.*)?");
        assertThat(username).isNotBlank();
        assertThat(password).isNotBlank();
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var tables = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE()")) {
            assertThat(tables.next()).isTrue();
            assertThat(tables.getInt(1)).as("requires an empty disposable fixture database").isZero();
        }
        Flyway legacy = configuration(url, username, password).target(MigrationVersion.fromVersion(Integer.toString(version))).load();
        assertThat(legacy.migrate().migrationsExecuted).isEqualTo(version);
        Map<String, Integer> oldChecksums = Arrays.stream(legacy.info().applied())
                .collect(Collectors.toMap(migration -> migration.getVersion().getVersion(), migration -> migration.getChecksum()));
        String userId = UUID.randomUUID().toString();
        String conversationId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String documentId = UUID.randomUUID().toString();
        try (var connection = DriverManager.getConnection(url, username, password)) {
            try (var user = connection.prepareStatement("""
                    INSERT INTO app_users (id,email,password_hash,full_name,role,status,created_at,updated_at)
                    VALUES (?,?,'fixture-not-a-password-hash','Legacy sales','ADMIN','ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                    """)) {
                user.setString(1, userId); user.setString(2, "legacy-v" + version + "@example.test");
                assertThat(user.executeUpdate()).isEqualTo(1);
            }
            try (var conversation = connection.prepareStatement("""
                    INSERT INTO rag_conversations (id,owner_id,title,created_at,updated_at)
                    VALUES (?,?,'Legacy customer context',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))
                    """)) {
                conversation.setString(1, conversationId); conversation.setString(2, userId);
                assertThat(conversation.executeUpdate()).isEqualTo(1);
            }
            try (var message = connection.prepareStatement("""
                    INSERT INTO rag_conversation_messages (id,conversation_id,role,content,created_at)
                    VALUES (?,?,'USER','Khách hàng có 37 sales; mã TV-4712',CURRENT_TIMESTAMP(6))
                    """)) {
                message.setString(1, messageId); message.setString(2, conversationId);
                assertThat(message.executeUpdate()).isEqualTo(1);
            }
            try (var document = connection.prepareStatement("""
                    INSERT INTO rag_documents (id,owner_id,original_filename,storage_key,content_type,size_bytes,
                    requested_chunk_size,requested_chunk_overlap,status,produced_chunk_count,created_at)
                    VALUES (?,?,'legacy.txt',?,'text/plain',12,900,120,'READY',1,CURRENT_TIMESTAMP(6))
                    """)) {
                document.setString(1, documentId); document.setString(2, userId); document.setString(3, "fixture/" + documentId + ".txt");
                assertThat(document.executeUpdate()).isEqualTo(1);
            }
        }
        Flyway latest = configuration(url, username, password).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(15 - version);
        latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("15");
        assertThat(latest.info().pending()).isEmpty();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        Map<String, Integer> newChecksums = Arrays.stream(latest.info().applied())
                .collect(Collectors.toMap(migration -> migration.getVersion().getVersion(), migration -> migration.getChecksum()));
        assertThat(newChecksums).containsAllEntriesOf(oldChecksums);
        try (var connection = DriverManager.getConnection(url, username, password)) {
            try (var statement = connection.prepareStatement("""
                    SELECT u.auth_version,u.active_session_id,c.title,c.deleted_at,m.content,d.status,d.attempt_count,d.produced_chunk_count
                    FROM app_users u JOIN rag_conversations c ON c.owner_id=u.id
                    JOIN rag_conversation_messages m ON m.conversation_id=c.id JOIN rag_documents d ON d.owner_id=u.id
                    WHERE u.id=? AND c.id=? AND m.id=? AND d.id=?
                    """)) {
                statement.setString(1, userId); statement.setString(2, conversationId);
                statement.setString(3, messageId); statement.setString(4, documentId);
                try (var row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong("auth_version")).isZero();
                    assertThat(row.getString("active_session_id")).isNull();
                    assertThat(row.getString("title")).isEqualTo("Legacy customer context");
                    assertThat(row.getTimestamp("deleted_at")).isNull();
                    assertThat(row.getString("content")).isEqualTo("Khách hàng có 37 sales; mã TV-4712");
                    assertThat(row.getString("status")).isEqualTo("READY");
                    assertThat(row.getInt("attempt_count")).isZero();
                    assertThat(row.getInt("produced_chunk_count")).isEqualTo(1);
                    assertThat(row.next()).isFalse();
                }
            }
        }
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration configuration(String url, String username, String password) {
        return Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT",
                        "idempotency_key_type", "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                .cleanDisabled(true);
    }
}
