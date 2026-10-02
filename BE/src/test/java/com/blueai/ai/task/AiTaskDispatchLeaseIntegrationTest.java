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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pause after the durable claim commits, not inside a provider or an open transaction. */
class AiTaskDispatchLeaseIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private AiTaskRecoveryService recovery;
    private AiTaskService tasks;
    private AfterClaimTransactions transactions;
    private AiTaskProperties properties;
    private AiClientPrincipal client;
    private String documentId;
    private AiCompletionProvider completion;
    private AiRetrievalProvider retrieval;

    @BeforeEach
    void fixtures() throws Exception {
        String url = "jdbc:h2:mem:dispatch-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        var source = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "CLOB", "long_text_type", "LONGTEXT", "idempotency_key_type", "VARCHAR(128)"))
                .cleanDisabled(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transactions = new AfterClaimTransactions(manager);
        // Recovery has its own template so the barrier only pauses the service's committed claim.
        recovery = new AiTaskRecoveryService(jdbc, mapper, new TransactionTemplate(manager));
        User owner = new User(UUID.randomUUID() + "@dispatch.example.test", "not-a-password-hash", "Dispatch test", UserRole.ADMIN);
        client = new AiClientPrincipal(UUID.randomUUID().toString(), owner.getId());
        Instant now = Instant.now();
        jdbc.update("""
                insert into app_users(id,email,password_hash,full_name,role,status,created_at,updated_at)
                values(?,?,?,'Dispatch test','ADMIN','ACTIVE',?,?)
                """, owner.getId(), owner.getEmail(), owner.getPasswordHash(), now, now);
        jdbc.update("insert into ai_integration_clients(id,owner_id,name,credential_hash,created_at) values(?,?,'Dispatch test',?,?)",
                client.clientId(), client.ownerId(), client.clientId().replace("-", "").repeat(2), now);
        jdbc.update("insert into ai_integration_capabilities(client_id,capability) values(?,'CLASSIFY'),(?,'RAG_ANSWER')", client.clientId(), client.clientId());
        RagDocument document = new RagDocument(owner, "dispatch.txt", UUID.randomUUID().toString(), "text/plain", 1, 100, 10);
        document.markReady(1);
        documentId = document.getId();
        jdbc.update("""
                insert into rag_documents(id,owner_id,original_filename,storage_key,content_type,size_bytes,
                    requested_chunk_size,requested_chunk_overlap,status,created_at)
                values(?,?,'dispatch.txt',?,'text/plain',1,100,10,'READY',?)
                """, documentId, client.ownerId(), document.getStorageKey(), now);
        jdbc.update("insert into ai_integration_document_grants(client_id,document_id) values(?,?)", client.clientId(), documentId);
        var users = mock(UserRepository.class);
        var documents = mock(RagDocumentRepository.class);
        when(users.findById(client.ownerId())).thenReturn(Optional.of(owner));
        when(documents.findByIdAndOwner_Id(documentId, client.ownerId())).thenReturn(Optional.of(document));
        completion = mock(AiCompletionProvider.class);
        retrieval = mock(AiRetrievalProvider.class);
        when(completion.complete(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return "{\"label\":\"sales\",\"abstain\":false,\"reason\":\"explicit\"}";
        });
        properties = new AiTaskProperties();
        properties.setDeadline(Duration.ofSeconds(10));
        tasks = new AiTaskService(jdbc, mapper, transactions, documents, users, completion, retrieval, properties, recovery);
    }

    @AfterEach
    void closeOwnDatabase() {
        if (transactions != null) transactions.release.countDown();
        if (jdbc != null) jdbc.execute("SHUTDOWN"); // This UUID-named in-memory database belongs only to this test.
    }

    @Test
    void workerPausedAfterClaimDoesNotDispatchAfterAnotherReplicaExpiresIt() throws Exception {
        JsonNode result = pausedSubmit(classifyBody(), id -> {
            expire(id);
            assertThat(recovery.recoverClient(client.clientId())).isEqualTo(1);
        });
        assertThat(result.path("status").asText()).isEqualTo("FAILED");
        assertThat(result.at("/error/code").asText()).isEqualTo("AI_TASK_EXPIRED");
        String id = result.path("taskId").asText();
        String durable = jdbc.queryForObject("select response_json from ai_task_ledger where id=?", String.class, id);
        assertThat(mapper.readTree(durable)).isEqualTo(result);
        assertThat(mapper.<JsonNode>valueToTree(tasks.submit(client, "dispatch:barrier", classifyBody()).getBody())).isEqualTo(result);
        assertQuotaAndNoEvidence(id);
        verifyNoInteractions(completion, retrieval);
    }

    @Test
    void workerPausedAfterClaimExpiresDurablyWithoutWaitingForTheSweep() throws Exception {
        JsonNode result = pausedSubmit(classifyBody(), this::expire);
        assertThat(result.at("/error/code").asText()).isEqualTo("AI_TASK_EXPIRED");
        assertThat(jdbc.queryForObject("select error_code from ai_task_ledger where id=?", String.class, result.path("taskId").asText())).isEqualTo("AI_TASK_EXPIRED");
        assertThat(recovery.recoverClient(client.clientId())).isZero();
        assertQuotaAndNoEvidence(result.path("taskId").asText());
        verifyNoInteractions(completion, retrieval);
    }

    @Test
    void terminalClaimIsNeverReexecutedOrOverwrittenEvenIfItsLeaseIsStillLive() throws Exception {
        String terminal = "{\"status\":\"FAILED\",\"capability\":\"CLASSIFY\",\"error\":{\"code\":\"ALREADY_TERMINAL\"}}";
        JsonNode result = pausedSubmit(classifyBody(), id -> jdbc.update("update ai_task_ledger set status='FAILED',response_json=?,error_code='ALREADY_TERMINAL' where id=?", terminal, id));
        assertThat(result).isEqualTo(mapper.readTree(terminal));
        assertThat(jdbc.queryForObject("select response_json from ai_task_ledger where client_id=?", String.class, client.clientId())).isEqualTo(terminal);
        verifyNoInteractions(completion, retrieval);
    }

    @Test
    void finalizationGraceIsNotAnAdditionalProviderBudget() throws Exception {
        JsonNode result = pausedSubmit(classifyBody(), id -> jdbc.update("update ai_task_ledger set expires_at=? where id=?",
                recovery.databaseNow().plus(AiTaskRecoveryService.FINALIZATION_GRACE).minusSeconds(1), id));
        assertThat(result.at("/error/code").asText()).isEqualTo("AI_TASK_DEADLINE_EXCEEDED");
        assertQuotaAndNoEvidence(result.path("taskId").asText());
        verifyNoInteractions(completion, retrieval);
    }

    @Test
    void providerReceivesTheDeadlineCapturedByClaimNotConfigurationReadAfterCommit() throws Exception {
        var receivedDeadline = new AtomicReference<Instant>();
        when(completion.complete(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            receivedDeadline.set(invocation.getArgument(3));
            return "{\"label\":\"sales\",\"abstain\":false,\"reason\":\"explicit\"}";
        });
        var beforeClaim = Instant.now();
        JsonNode result = pausedSubmit(classifyBody(), id -> properties.setDeadline(Duration.ofSeconds(45)));
        assertThat(result.path("status").asText()).isEqualTo("COMPLETED");
        Instant created = jdbc.queryForObject("select created_at from ai_task_ledger where id=?", Timestamp.class, result.path("taskId").asText()).toInstant();
        assertThat(receivedDeadline.get()).isAfterOrEqualTo(beforeClaim.plusSeconds(10)).isBefore(created.plusSeconds(11));
        verify(completion, times(1)).complete(anyString(), anyString(), anyList(), any());
        verifyNoInteractions(retrieval);
    }

    @Test
    void ownerDisabledBetweenClaimAndDispatchPreventsAnyProviderCall() throws Exception {
        JsonNode result = pausedSubmit(classifyBody(), id -> jdbc.update("update app_users set status='DISABLED' where id=?", client.ownerId()));
        assertThat(result.at("/error/code").asText()).isEqualTo("OWNER_INACTIVE");
        assertQuotaAndNoEvidence(result.path("taskId").asText());
        verifyNoInteractions(completion, retrieval);
    }

    @Test
    void clientRevokedBetweenClaimAndDispatchPreventsAnyProviderCall() throws Exception {
        JsonNode result = pausedSubmit(classifyBody(), id -> jdbc.update("update ai_integration_clients set active=false,revoked_at=? where id=?", Instant.now(), client.clientId()));
        assertThat(result.at("/error/code").asText()).isEqualTo("INVALID_INTEGRATION_CREDENTIAL");
        assertQuotaAndNoEvidence(result.path("taskId").asText());
        verifyNoInteractions(completion, retrieval);
    }

    @Test
    void expirationDuringRetrievalPreventsTheCompletionStageAndKeepsTheExpiredResponse() throws Exception {
        when(retrieval.search(anyString(), anyString(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            expire(jdbc.queryForObject("select id from ai_task_ledger where client_id=?", String.class, client.clientId()));
            assertThat(recovery.recoverClient(client.clientId())).isEqualTo(1);
            return mapper.readTree("""
                    [{"documentId":"%s","chunkId":"chunk-1","content":"private source"}]
                    """.formatted(documentId));
        });
        JsonNode result = pausedSubmit(mapper.readTree("""
                {"capability":"RAG_ANSWER","input":{"text":"question"},"configuration":{"documentIds":["%s"]}}
                """.formatted(documentId)), id -> {});
        assertThat(result.at("/error/code").asText()).isEqualTo("AI_TASK_EXPIRED");
        assertQuotaAndNoEvidence(result.path("taskId").asText());
        verify(retrieval, times(1)).search(anyString(), anyString(), anyList(), any());
        verifyNoInteractions(completion);
    }

    private JsonNode pausedSubmit(JsonNode body, Consumer<String> afterCommit) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var worker = executor.submit(() -> tasks.submit(client, "dispatch:barrier", body));
            assertThat(transactions.committed.await(5, TimeUnit.SECONDS)).isTrue();
            String id = jdbc.queryForObject("select id from ai_task_ledger where client_id=? and idempotency_key='dispatch:barrier'", String.class, client.clientId());
            assertThat(jdbc.queryForObject("select status from ai_task_ledger where id=?", String.class, id)).isEqualTo("PROCESSING");
            verifyNoInteractions(completion, retrieval);
            afterCommit.accept(id);
            transactions.release.countDown();
            var response = worker.get(10, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            return mapper.valueToTree(response.getBody());
        } finally {
            transactions.release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void expire(String id) {
        jdbc.update("update ai_task_ledger set expires_at=? where id=?", recovery.databaseNow().minusSeconds(1), id);
    }

    private void assertQuotaAndNoEvidence(String id) {
        assertThat(jdbc.queryForObject("select rate_count from ai_integration_clients where id=?", Integer.class, client.clientId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ai_task_evidence where task_id=?", Integer.class, id)).isZero();
    }

    private JsonNode classifyBody() throws Exception {
        return mapper.readTree("""
                {"capability":"CLASSIFY","input":{"text":"hello"},"configuration":{"allowedLabels":["sales"]}}
                """);
    }

    private static final class AfterClaimTransactions extends TransactionTemplate {
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean firstCommit = new AtomicBoolean(true);

        AfterClaimTransactions(DataSourceTransactionManager manager) { super(manager); }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            T result = super.execute(action);
            // Nested authorization callbacks join claimTask's transaction; only pause after outer commit.
            if (!TransactionSynchronizationManager.isActualTransactionActive() && firstCommit.compareAndSet(true, false)) {
                committed.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("dispatch barrier was not released");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
            return result;
        }
    }
}
