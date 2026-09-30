package com.blueai.migration;

import com.blueai.BlueAiAuthApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a new H2 database applies the full migration chain and that
 * the application can start with Hibernate validation enabled.
 */
@SpringBootTest(classes = BlueAiAuthApplication.class)
@ActiveProfiles("test")
class FlywayMigrationIntegrationTest {

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "app_users",
            "refresh_tokens",
            "rag_conversations",
            "rag_conversation_messages",
            "rag_conversation_summaries",
            "rag_conversation_links",
            "rag_documents",
            "rag_user_memories",
            "studio_bots",
            "studio_bot_releases",
            "studio_bot_dialog_nodes",
            "studio_bot_entities",
            "studio_bot_intents",
            "studio_bot_entity_values",
            "studio_bot_entity_synonyms",
            "studio_bot_intent_examples",
            "studio_bot_sessions",
            "studio_bot_turns",
            "password_reset_tokens",
            "auth_audit_logs",
            "ai_integration_clients",
            "ai_integration_capabilities",
            "ai_integration_document_grants",
            "ai_task_ledger",
            "ai_task_evidence",
            "gemini_usage_events"
    );

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesFullMigrationChainAndLeavesExpectedSchemaForHibernateValidation() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
        flyway.validate();

        Set<String> usageColumns = jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE LOWER(TABLE_SCHEMA) = 'public' "
                                + "AND LOWER(TABLE_NAME) = 'gemini_usage_events' "
                                + "AND IS_NULLABLE = 'NO'",
                        String.class)
                .stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(usageColumns).containsExactlyInAnyOrder(
                "id", "model", "prompt_tokens", "completion_tokens", "total_tokens", "recorded_at");

        Integer purgeAttemptColumnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_SCHEMA) = 'public' "
                        + "AND LOWER(TABLE_NAME) = 'rag_documents' "
                        + "AND LOWER(COLUMN_NAME) = 'purge_attempted_at' "
                        + "AND LOWER(DATA_TYPE) = 'timestamp'",
                Integer.class);
        assertThat(purgeAttemptColumnCount).isEqualTo(1);

        Set<String> actualTables = jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE LOWER(TABLE_SCHEMA) = 'public' "
                                + "AND TABLE_TYPE = 'BASE TABLE' "
                                + "AND LOWER(TABLE_NAME) <> 'flyway_schema_history'",
                        String.class)
                .stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        assertThat(actualTables).containsAll(EXPECTED_TABLES);

        Integer authVersionColumnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_SCHEMA) = 'public' "
                        + "AND LOWER(TABLE_NAME) = 'app_users' "
                        + "AND LOWER(COLUMN_NAME) = 'auth_version' "
                        + "AND LOWER(DATA_TYPE) = 'bigint' "
                        + "AND IS_NULLABLE = 'NO' "
                        + "AND CAST(COLUMN_DEFAULT AS VARCHAR) = '0'",
                Integer.class);
        assertThat(authVersionColumnCount).isEqualTo(1);

        Set<String> actualIndexes = jdbcTemplate.queryForList(
                        "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                                + "WHERE LOWER(TABLE_SCHEMA) = 'public'",
                        String.class)
                .stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(actualIndexes).contains(
                "idx_rag_conversation_owner_deleted_updated",
                "idx_rag_conversation_deleted_at",
                "idx_rag_document_owner_deleted_created",
                "idx_rag_document_status_deleted_next_created",
                "idx_rag_document_status_deleted_processing",
                "idx_rag_document_deleted_at",
                "idx_password_reset_user_expires",
                "idx_auth_audit_created_at",
                "idx_ai_client_owner_active",
                "idx_ai_document_grant_document",
                "idx_ai_task_client_created",
                "idx_ai_task_status_created",
                "idx_ai_task_evidence_document",
                "idx_gemini_usage_recorded_at"
        );
    }
}
