package com.blueai.ai.task;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.document.model.RagDocument;
import com.blueai.rag.document.repository.RagDocumentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** MySQL repeatable-read snapshots must never authorize a saved answer. No shared fixtures. */
@EnabledIfEnvironmentVariable(named = "MYSQL_AUTH_TEST_URL", matches = ".+")
class AiTaskMySqlAuthorizationIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AiTaskService tasks;
    private AiCompletionProvider completion;
    private AiRetrievalProvider retrieval;
    private AiClientPrincipal client;
    private String documentId;
    private String foreignClientId;
    private final AtomicBoolean pauseFinalAuthorization = new AtomicBoolean();
    private final AtomicBoolean providerReturned = new AtomicBoolean();
    private final CountDownLatch snapshotRead = new CountDownLatch(1);
    private final CountDownLatch continueAuthorization = new CountDownLatch(1);

    @BeforeEach
    void fixtures() throws Exception {
        String url = System.getenv("MYSQL_AUTH_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/blueai_auth_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Authorization tests require a disposable blueai_auth_test database.");
        }
        String username = System.getenv("MYSQL_AUTH_TEST_USERNAME");
        String password = System.getenv("MYSQL_AUTH_TEST_PASSWORD");
        // Migrate to whatever the current latest schema is, rather than pinning a version.
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT",
                        "idempotency_key_type", "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                .cleanDisabled(true).load().migrate();
        var source = new DriverManagerDataSource(url, username, password);
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        User owner = new User(UUID.randomUUID() + "@authorization.example.test", "not-a-password-hash", "Authorization test", UserRole.ADMIN);
        client = new AiClientPrincipal(UUID.randomUUID().toString(), owner.getId());
        Instant now = Instant.now();
        jdbc.update("""
                insert into app_users(id,email,password_hash,full_name,role,status,created_at,updated_at)
                values(?,?,?,'Authorization test','ADMIN','ACTIVE',?,?)
                """, owner.getId(), owner.getEmail(), owner.getPasswordHash(), now, now);
        insertClient(client.clientId());
        jdbc.update("insert into ai_integration_capabilities(client_id,capability) values(?,'CLASSIFY'),(?,'RAG_ANSWER')", client.clientId(), client.clientId());
        RagDocument document = new RagDocument(owner, "evidence.txt", UUID.randomUUID().toString(), "text/plain", 1, 100, 10);
        document.markReady(1);
        documentId = document.getId();
        jdbc.update("""
                insert into rag_documents(id,owner_id,original_filename,storage_key,content_type,size_bytes,
                    requested_chunk_size,requested_chunk_overlap,status,created_at)
                values(?,?,'evidence.txt',?,'text/plain',1,100,10,'READY',?)
                """, documentId, client.ownerId(), document.getStorageKey(), now);
        jdbc.update("insert into ai_integration_document_grants(client_id,document_id) values(?,?)", client.clientId(), documentId);

        var users = mock(UserRepository.class);
        var documents = mock(RagDocumentRepository.class);
        // Deliberately retain old entities: neither may be used to decide current authorization.
        when(users.findById(client.ownerId())).thenReturn(Optional.of(owner));
        when(documents.findByIdAndOwner_Id(documentId, client.ownerId())).thenReturn(Optional.of(document));
        completion = mock(AiCompletionProvider.class);
        retrieval = mock(AiRetrievalProvider.class);
        when(retrieval.search(anyString(), anyString(), anyList(), any())).thenReturn(mapper.readTree("""
                [{"documentId":"%s","chunkId":"chunk-1","content":"approved evidence"}]
                """.formatted(documentId)));
        when(completion.complete(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            providerReturned.set(true);
            return invocation.<String>getArgument(0).startsWith("Classify")
                    ? "{\"label\":\"sales\",\"abstain\":false,\"reason\":\"explicit\"}"
                    : "{\"answer\":\"private answer\",\"abstain\":false,\"citations\":[\"chunk-1\"]}";
        });
        var recovery = spy(new AiTaskRecoveryService(jdbc, mapper, transactions));
        doAnswer(invocation -> {
            int expired = (int) invocation.callRealMethod();
            if (providerReturned.get() && pauseFinalAuthorization.compareAndSet(true, false)) {
                // expireLockedClient's nonlocking ledger SELECT has established the old RR snapshot.
                assertThat(jdbc.queryForObject("select deleted_at from rag_documents where id=?", java.sql.Timestamp.class, documentId)).isNull();
                snapshotRead.countDown();
                await(continueAuthorization);
            }
            return expired;
        }).when(recovery).expireLockedClient(client.clientId());
        var properties = new AiTaskProperties();
        properties.setDeadline(Duration.ofSeconds(45));
        tasks = new AiTaskService(jdbc, mapper, transactions, documents, users, completion, retrieval, properties, recovery);
    }

    @AfterEach
    void cleanup() {
        continueAuthorization.countDown();
        if (jdbc == null || client == null) return;
        // All deletes are scoped to UUIDs created by this test; never clean the shared database.
        for (String id : List.of(client.clientId(), foreignClientId == null ? client.clientId() : foreignClientId)) {
            jdbc.update("delete from ai_task_ledger where client_id=?", id);
            jdbc.update("delete from ai_integration_document_grants where client_id=?", id);
            jdbc.update("delete from ai_integration_capabilities where client_id=?", id);
            jdbc.update("delete from ai_integration_clients where id=?", id);
        }
        if (documentId != null) jdbc.update("delete from rag_documents where id=?", documentId);
        jdbc.update("delete from app_users where id=?", client.ownerId());
    }

    @ParameterizedTest
    @EnumSource(AccessMutation.class)
    void committedMutationBetweenRecoverySnapshotAndFinalAuthorizationSuppressesAnswer(AccessMutation mutation) throws Exception {
        pauseFinalAuthorization.set(true);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var worker = executor.submit(() -> tasks.submit(client, "final:barrier", ragBody()));
            assertThat(snapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
            // Separate connection/transaction commits while finalization retains its old snapshot.
            transactions.executeWithoutResult(status -> mutate(mutation));
            continueAuthorization.countDown();
            var response = worker.get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(mapper.valueToTree(response.getBody()).at("/error/code").asText()).isEqualTo(mutation.code);
            assertThat(mapper.writeValueAsString(response.getBody())).doesNotContain("private answer");
            String taskId = taskId("final:barrier");
            assertThat(jdbc.queryForObject("select status from ai_task_ledger where id=?", String.class, taskId)).isEqualTo("FAILED");
            assertThat(jdbc.queryForObject("select response_json from ai_task_ledger where id=?", String.class, taskId)).doesNotContain("private answer");
            assertThat(jdbc.queryForObject("select count(*) from ai_task_evidence where task_id=?", Integer.class, taskId)).isZero();
            assertThat(jdbc.queryForObject("select rate_count from ai_integration_clients where id=?", Integer.class, client.clientId())).isEqualTo(1);
            verify(completion, times(1)).complete(anyString(), anyString(), anyList(), any());
        } finally {
            continueAuthorization.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(AccessMutation.class)
    void pollingAndReplayRejectCommittedMutationDespiteAnOlderOuterSnapshot(AccessMutation mutation) throws Exception {
        tasks.submit(client, "saved:answer", ragBody());
        String id = taskId("saved:answer");
        var executor = Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(status -> {
                // Establish a RR snapshot before a second connection commits its mutation.
                jdbc.queryForList("select id from rag_documents where id=?", documentId);
                try {
                    executor.submit(() -> transactions.executeWithoutResult(s -> mutate(mutation))).get(10, TimeUnit.SECONDS);
                } catch (Exception exception) { throw new AssertionError(exception); }
                String expected = mutation == AccessMutation.OWNER_DISABLE ? "OWNER_INACTIVE" : "AI_TASK_EVIDENCE_REVOKED";
                assertThatThrownBy(() -> tasks.get(client, id)).isInstanceOfSatisfying(AiTaskException.class, e -> assertThat(e.code).isEqualTo(expected));
                assertThatThrownBy(() -> tasks.submit(client, "saved:answer", ragBody())).isInstanceOfSatisfying(AiTaskException.class, e -> assertThat(e.code).isEqualTo(expected));
                status.setRollbackOnly(); // A rejected nested authorization deliberately marks the outer tx rollback-only.
            });
            String expected = mutation == AccessMutation.OWNER_DISABLE ? "OWNER_INACTIVE" : "AI_TASK_EVIDENCE_REVOKED";
            assertThatThrownBy(() -> tasks.submit(client, "saved:answer", ragBody())).isInstanceOfSatisfying(AiTaskException.class, e -> assertThat(e.code).isEqualTo(expected));
            verify(completion, times(1)).complete(anyString(), anyString(), anyList(), any());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void clientRevocationIsCurrentEvenInsideAnOlderPollingSnapshot() throws Exception {
        tasks.submit(client, "saved:classify", classifyBody());
        var executor = Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.queryForList("select active from ai_integration_clients where id=?", client.clientId());
                try {
                    executor.submit(() -> jdbc.update("update ai_integration_clients set active=false,revoked_at=? where id=?", Instant.now(), client.clientId())).get(10, TimeUnit.SECONDS);
                } catch (Exception exception) { throw new AssertionError(exception); }
                assertThatThrownBy(() -> tasks.get(client, taskId("saved:classify"))).isInstanceOfSatisfying(AiTaskException.class,
                        e -> assertThat(e.code).isEqualTo("INVALID_INTEGRATION_CREDENTIAL"));
                status.setRollbackOnly();
            });
            assertThatThrownBy(() -> tasks.submit(client, "saved:classify", classifyBody())).isInstanceOfSatisfying(AiTaskException.class,
                    e -> assertThat(e.code).isEqualTo("INVALID_INTEGRATION_CREDENTIAL"));
            verify(completion, times(1)).complete(anyString(), anyString(), anyList(), any());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void caseDistinctKeysCreateDistinctTasksAndExactReplayNeitherCallsNorChargesAgain() throws Exception {
        var first = tasks.submit(client, "execution:A", classifyBody());
        var second = tasks.submit(client, "execution:a", classifyBody());
        assertThat(mapper.valueToTree(first.getBody()).path("taskId").asText()).isNotEqualTo(mapper.valueToTree(second.getBody()).path("taskId").asText());
        assertThat(tasks.submit(client, "execution:A", classifyBody()).getBody()).isEqualTo(first.getBody());
        assertThat(tasks.submit(client, "execution:a", classifyBody()).getBody()).isEqualTo(second.getBody());
        assertThat(jdbc.queryForObject("select count(*) from ai_task_ledger where client_id=?", Integer.class, client.clientId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("select rate_count from ai_integration_clients where id=?", Integer.class, client.clientId())).isEqualTo(2);
        verify(completion, times(2)).complete(anyString(), anyString(), anyList(), any());
        verifyNoInteractions(retrieval);
        assertThatThrownBy(() -> tasks.submit(client, "execution:A", mapper.readTree("""
                {"capability":"CLASSIFY","input":{"text":"different"},"configuration":{"allowedLabels":["sales"]}}
                """))).isInstanceOfSatisfying(AiTaskException.class, e -> assertThat(e.code).isEqualTo("IDEMPOTENCY_CONFLICT"));
        foreignClientId = UUID.randomUUID().toString();
        insertClient(foreignClientId);
        assertThatThrownBy(() -> tasks.get(new AiClientPrincipal(foreignClientId, client.ownerId()), taskId("execution:A")))
                .isInstanceOfSatisfying(AiTaskException.class, e -> assertThat(e.status).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void insertClient(String id) {
        jdbc.update("insert into ai_integration_clients(id,owner_id,name,credential_hash,created_at) values(?,?,'Authorization test',?,?)",
                id, client.ownerId(), id.replace("-", "").repeat(2), Instant.now());
    }

    private void mutate(AccessMutation mutation) {
        int rows = switch (mutation) {
            case DOCUMENT_DELETE -> jdbc.update("update rag_documents set deleted_at=? where id=?", Instant.now(), documentId);
            case GRANT_REVOKE -> jdbc.update("delete from ai_integration_document_grants where client_id=? and document_id=?", client.clientId(), documentId);
            case OWNER_DISABLE -> jdbc.update("update app_users set status='DISABLED',auth_version=auth_version+1 where id=?", client.ownerId());
        };
        assertThat(rows).isEqualTo(1);
    }

    private String taskId(String key) {
        return jdbc.queryForObject("select id from ai_task_ledger where client_id=? and idempotency_key=?", String.class, client.clientId(), key);
    }

    private JsonNode ragBody() throws Exception {
        return mapper.readTree("""
                {"capability":"RAG_ANSWER","input":{"text":"question"},"configuration":{"documentIds":["%s"]}}
                """.formatted(documentId));
    }

    private JsonNode classifyBody() throws Exception {
        return mapper.readTree("""
                {"capability":"CLASSIFY","input":{"text":"hello"},"configuration":{"allowedLabels":["sales"]}}
                """);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("authorization barrier was not released");
    }

    enum AccessMutation {
        DOCUMENT_DELETE("DOCUMENT_NOT_READY"), GRANT_REVOKE("DOCUMENT_NOT_GRANTED"), OWNER_DISABLE("OWNER_INACTIVE");
        final String code;
        AccessMutation(String code) { this.code = code; }
    }
}
