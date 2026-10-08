package com.blueai.ai.task;

import com.blueai.ai.task.service.AiTaskRecoveryService;
import com.blueai.ai.task.repository.AiTaskRepository;
import com.blueai.ai.task.repository.IntegrationClientRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Actual MySQL locking, durable restart state, rollback and competing replicas. */
@EnabledIfEnvironmentVariable(named = "MYSQL_AUTH_TEST_URL", matches = ".+")
class AiTaskMySqlRecoveryIntegrationTest {
    private static final Pattern ACTUAL_INDEX_WORK = Pattern.compile(
            "\\(actual time=[^)]*?\\brows=(\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s+loops=(\\d+)\\)");
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ObjectMapper mapper;
    private String ownerId;
    private String clientId;
    private final List<String> additionalClients = new ArrayList<>();

    @BeforeEach
    void fixtures() {
        String url = System.getenv("MYSQL_AUTH_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/blueai_auth_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Recovery tests require a disposable blueai_auth_test database.");
        }
        String username = System.getenv("MYSQL_AUTH_TEST_USERNAME");
        String password = System.getenv("MYSQL_AUTH_TEST_PASSWORD");
        Flyway.configure().dataSource(url, username, password).locations("classpath:db/migration")
                .placeholders(Map.of("text_type", "LONGTEXT", "long_text_type", "LONGTEXT",
                        "idempotency_key_type", "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"))
                .cleanDisabled(true).load().migrate();
        DriverManagerDataSource source = new DriverManagerDataSource(url, username, password);
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        mapper = new ObjectMapper();
        ownerId = UUID.randomUUID().toString();
        clientId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("""
                insert into app_users(id,email,password_hash,full_name,role,status,created_at,updated_at)
                values(?,?,?,'Recovery test','ADMIN','ACTIVE',?,?)
                """, ownerId, ownerId + "@recovery.example.test", "not-a-real-password-hash", now, now);
        jdbc.update("""
                insert into ai_integration_clients(id,owner_id,name,credential_hash,created_at,rate_count)
                values(?,?,'Recovery test',?,?,7)
                """, clientId, ownerId, clientId.replace("-", "").repeat(2), now);
    }

    @AfterEach
    void cleanup() {
        if (jdbc == null || clientId == null) return;
        var clients = new ArrayList<>(additionalClients);
        clients.add(clientId);
        for (String id : clients) {
            jdbc.update("delete from ai_task_ledger where client_id=?", id);
            jdbc.update("delete from ai_integration_clients where id=?", id);
        }
        jdbc.update("delete from app_users where id=?", ownerId);
    }

    @Test
    void restartedServiceRecoversExpiredRowsButNotLiveOrCompletedTasks() {
        String expired = claim("expired", true);
        String live = claim("live", false);
        String completed = claim("completed", true);
        jdbc.update("update ai_task_ledger set status='COMPLETED',response_json='{}' where id=?", completed);
        // A new service instance has no in-memory knowledge of the original worker.
        AiTaskRecoveryService restarted = new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions);
        assertThat(restarted.recoverExpiredTasks()).isEqualTo(1);
        assertThat(restarted.recoverExpiredTasks()).isZero();
        assertThat(jdbc.queryForObject("select error_code from ai_task_ledger where id=?", String.class, expired)).isEqualTo("AI_TASK_EXPIRED");
        assertThat(jdbc.queryForObject("select status from ai_task_ledger where id=?", String.class, live)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select response_json from ai_task_ledger where id=?", String.class, completed)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("select rate_count from ai_integration_clients where id=?", Integer.class, clientId)).isEqualTo(7);
    }

    @Test
    void competingReplicasRecoverEachExpiredClaimExactlyOnce() throws Exception {
        for (int i = 0; i < 4; i++) claim("crashed:" + i, true);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions).recoverExpiredTasks();
            });
            var second = executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions).recoverExpiredTasks();
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(4);
            assertThat(jdbc.queryForObject("select count(*) from ai_task_ledger where client_id=? and status='PROCESSING'", Integer.class, clientId)).isZero();
            assertThat(jdbc.queryForList("select error_code from ai_task_ledger where client_id=?", String.class, clientId))
                    .containsOnly("AI_TASK_EXPIRED");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void persistenceFailureRollsBackAndLaterRecoveryCanRetryWithoutChangingTheKey() throws Exception {
        String id = claim("recovery:retry", true);
        claim("recovery:second", true);
        ObjectMapper broken = spy(new ObjectMapper());
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) {
                throw new JsonProcessingException("simulated second serialization failure") {};
            }
            return mapper.writeValueAsString(invocation.getArgument(0));
        }).when(broken).writeValueAsString(any());
        assertThatThrownBy(() -> new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), broken, transactions).recoverClient(clientId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select status from ai_task_ledger where id=?", String.class, id)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("select count(*) from ai_task_ledger where client_id=? and status='PROCESSING'", Integer.class, clientId)).isEqualTo(2);
        assertThat(new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions).recoverClient(clientId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select idempotency_key from ai_task_ledger where id=?", String.class, id)).isEqualTo("recovery:retry");
    }

    private String claim(String key, boolean expired) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("""
                insert into ai_task_ledger(id,client_id,idempotency_key,request_digest,capability,status,created_at,expires_at)
                values(?,?,?,?,'CLASSIFY','PROCESSING',?,?)
                """, id, clientId, key, "0".repeat(64), now.minusSeconds(120), now.plusSeconds(expired ? -60 : 60));
        return id;
    }

    @Test
    void candidatePageReadsOnlyOneIndexedPageFromALargeBacklog() {
        Instant expired = Instant.now().minusSeconds(60);
        Instant cutoff = expired.plusSeconds(1);
        String idPrefix = UUID.randomUUID().toString().substring(0, 24);
        List<String> orderedIds = java.util.stream.IntStream.range(0, 1_000)
                .mapToObj(index -> idPrefix + String.format(Locale.ROOT, "%012x", index)).toList();
        var rows = java.util.stream.IntStream.range(0, 1_000).mapToObj(index -> new Object[]{
                orderedIds.get(index), clientId, "backlog:" + index, "0".repeat(64), expired, expired
        }).toList();
        jdbc.batchUpdate("""
                insert into ai_task_ledger(id,client_id,idempotency_key,request_digest,capability,status,created_at,expires_at)
                values(?,?,?,?,'CLASSIFY','PROCESSING',?,?)
                """, rows);
        String candidateSql = """
                select id,client_id,expires_at from ai_task_ledger
                 where status='PROCESSING' and expires_at<=?
                 order by expires_at,id limit 100
                """;
        var page = jdbc.queryForList(candidateSql, cutoff);
        assertThat(page.stream().map(row -> (String) row.get("id")).toList())
                .containsExactlyElementsOf(orderedIds.subList(0, 100));
        String plan = jdbc.queryForObject("EXPLAIN ANALYZE " + candidateSql, String.class, cutoff);
        assertIndexedPageWork(plan, 100);
        String nextPageSql = """
                select id,client_id,expires_at from ai_task_ledger
                 where status='PROCESSING' and expires_at<=?
                   and (expires_at>? or (expires_at=? and id>?))
                 order by expires_at,id limit 100
                """;
        // Seek after the 900th row; the 900-row prefix must not be rescanned.
        String cursorId = orderedIds.get(899);
        var tail = jdbc.queryForList(nextPageSql, cutoff, expired, expired, cursorId);
        assertThat(tail.stream().map(row -> (String) row.get("id")).toList())
                .containsExactlyElementsOf(orderedIds.subList(900, 1_000));
        String nextPlan = jdbc.queryForObject("EXPLAIN ANALYZE " + nextPageSql, String.class,
                cutoff, expired, expired, cursorId);
        assertIndexedPageWork(nextPlan, 100);
        assertThat(new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions).recoverExpiredTasks()).isEqualTo(1_000);
    }

    @Test
    void indexWorkAssertionRejectsLimitCountsAndMultipliedScanWork() {
        String fullScan = """
                -> Limit: 100 row(s) (cost=10 rows=100) (actual time=0.1..1 rows=100 loops=1)
                    -> Covering index scan on ai_task_ledger using idx_ai_task_expiry_page (cost=10 rows=1000) (actual time=0.1..1 rows=1000 loops=1)
                """;
        assertThatThrownBy(() -> assertIndexedPageWork(fullScan, 100)).isInstanceOf(AssertionError.class);
        String repeatedScan = fullScan.replace("rows=1000 loops=1", "rows=100 loops=2");
        assertThatThrownBy(() -> assertIndexedPageWork(repeatedScan, 100)).isInstanceOf(AssertionError.class);
        String boundedScan = fullScan.replace("rows=1000 loops=1", "rows=50 loops=2");
        assertIndexedPageWork(boundedScan, 100);
        assertThatThrownBy(() -> assertIndexedPageWork("-> Sort: id\n" + boundedScan, 100)).isInstanceOf(AssertionError.class);
    }

    private void assertIndexedPageWork(String plan, long expectedRows) {
        assertThat(plan).isNotNull().doesNotContain("Sort:");
        List<String> accessNodes = plan.lines().filter(line -> line.contains("idx_ai_task_expiry_page")
                && line.matches(".*\\b[Ii]ndex (?:range scan|scan|lookup) on ai_task_ledger\\b.*")).toList();
        assertThat(accessNodes).as("one index-access node in plan:\n%s", plan).hasSize(1);
        var actual = ACTUAL_INDEX_WORK.matcher(accessNodes.getFirst());
        assertThat(actual.find()).as("actual index rows and loops in node: %s", accessNodes.getFirst()).isTrue();
        BigDecimal work = new BigDecimal(actual.group(1)).multiply(new BigDecimal(actual.group(2)));
        assertThat(work).as("actual index rows * loops, not LIMIT output or cost estimate")
                .isEqualByComparingTo(BigDecimal.valueOf(expectedRows));
    }

    @Test
    void continuallyNewExpiriesDoNotStarveAClientAfterItsLockIsReleased() throws Exception {
        String abandoned = claim("busy:abandoned", true);
        var recovery = spy(new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions));
        AtomicReference<Instant> clock = new AtomicReference<>(recovery.databaseNow());
        // Control only time; paging, NOWAIT, lock release and recovery commits use real MySQL.
        doAnswer(invocation -> clock.get()).when(recovery).databaseNow();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var owner = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from ai_integration_clients where id=? for update", String.class, clientId);
                locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertThat(recovery.recoverExpiredTasks()).isZero());
            assertThat(jdbc.queryForObject("select status from ai_task_ledger where id=?", String.class, abandoned)).isEqualTo("PROCESSING");
            release.countDown();
            owner.get(10, TimeUnit.SECONDS);

            for (int sweep = 0; sweep < 3; sweep++) {
                clock.set(clock.get().plusSeconds(10));
                String newcomer = insertAdditionalClient();
                String newer = insertExpiredClaim(newcomer, "tail:" + sweep, clock.get());
                // Every sweep has a newer eligible row, so a moving cutoff would never revisit the old client.
                assertThat(recovery.recoverExpiredTasks()).isEqualTo(sweep == 0 ? 2 : 1);
                assertThat(jdbc.queryForObject("select error_code from ai_task_ledger where id=?", String.class, abandoned)).isEqualTo("AI_TASK_EXPIRED");
                assertThat(jdbc.queryForObject("select error_code from ai_task_ledger where id=?", String.class, newer)).isEqualTo("AI_TASK_EXPIRED");
            }
            assertThat(jdbc.queryForObject("select rate_count from ai_integration_clients where id=?", Integer.class, clientId)).isEqualTo(7);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String insertAdditionalClient() {
        String id = UUID.randomUUID().toString();
        additionalClients.add(id);
        jdbc.update("insert into ai_integration_clients(id,owner_id,name,credential_hash,created_at) values(?,?,'Recovery tail test',?,?)",
                id, ownerId, id.replace("-", "").repeat(2), Instant.now());
        return id;
    }

    private String insertExpiredClaim(String ownerClient, String key, Instant expires) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into ai_task_ledger(id,client_id,idempotency_key,request_digest,capability,status,created_at,expires_at)
                values(?,?,?,?,'CLASSIFY','PROCESSING',?,?)
                """, id, ownerClient, key, "0".repeat(64), expires.minusSeconds(40), expires);
        return id;
    }

    @Test
    void recoveryNeverWaitsBehindAnActiveClientLock() throws Exception {
        String task = claim("busy-client", true);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var owner = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("select id from ai_integration_clients where id=? for update", String.class, clientId);
                locked.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () ->
                    assertThat(new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions).recoverExpiredTasks()).isZero());
            assertThat(jdbc.queryForObject("select status from ai_task_ledger where id=?", String.class, task)).isEqualTo("PROCESSING");
            release.countDown();
            owner.get(10, TimeUnit.SECONDS);
            assertThat(new AiTaskRecoveryService(new AiTaskRepository(jdbc), new IntegrationClientRepository(jdbc), mapper, transactions).recoverExpiredTasks()).isEqualTo(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
