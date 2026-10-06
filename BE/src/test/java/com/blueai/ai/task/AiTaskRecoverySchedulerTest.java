package com.blueai.ai.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AiTaskRecoverySchedulerTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(AiTaskRecoveryService.class, () -> mock(AiTaskRecoveryService.class))
            .withUserConfiguration(AiTaskRecoveryScheduler.class);

    @Test
    void recoveryIsEnabledByDefaultAndInvokesTheDurableSweep() {
        context.run(application -> {
            assertThat(application).hasSingleBean(AiTaskRecoveryScheduler.class);
            application.getBean(AiTaskRecoveryScheduler.class).recoverExpiredTasks();
            verify(application.getBean(AiTaskRecoveryService.class)).recoverExpiredTasks();
        });
    }

    @Test
    void isolatedTestsCanDisableTheBackgroundSweep() {
        context.withPropertyValues("app.ai.tasks.recovery-enabled=false").run(application -> {
            assertThat(application).doesNotHaveBean(AiTaskRecoveryScheduler.class);
            verifyNoInteractions(application.getBean(AiTaskRecoveryService.class));
        });
    }

    @Test
    void oneBrokenClientDoesNotStarveOtherClientsInTheSweep() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiTaskRecoveryService recovery = spy(new AiTaskRecoveryService(jdbc, new ObjectMapper(), mock(TransactionTemplate.class)));
        doReturn(Instant.now()).when(recovery).databaseNow();
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(candidate("task-1", "broken-client"), candidate("task-2", "healthy-client")));
        doThrow(new IllegalStateException("simulated persistence failure")).when(recovery).recoverClient("broken-client");
        doReturn(2).when(recovery).recoverClient("healthy-client");
        assertThat(recovery.recoverExpiredTasks()).isEqualTo(2);
        verify(recovery).recoverClient("broken-client");
        verify(recovery).recoverClient("healthy-client");
    }

    @Test
    void failedFullPageDoesNotStarveTheNextClientAndIsRetriedAfterWrapping() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiTaskRecoveryService recovery = spy(new AiTaskRecoveryService(jdbc, new ObjectMapper(), mock(TransactionTemplate.class)));
        Instant firstCutoff = Instant.parse("2026-01-02T00:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(firstCutoff);
        doAnswer(invocation -> clock.get()).when(recovery).databaseNow();
        List<Map<String, Object>> broken = IntStream.range(0, 100)
                .mapToObj(index -> candidate(new UUID(0, index + 1).toString(), "broken-" + index)).toList();
        Map<String, Object> healthy = candidate(new UUID(0, 101).toString(), "healthy-client");
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(broken, List.of(healthy), List.of(), broken);
        doThrow(new IllegalStateException("simulated persistence failure")).when(recovery).recoverClient(anyString());
        doReturn(1).when(recovery).recoverClient("healthy-client");

        assertThat(recovery.recoverExpiredTasks()).isZero();
        verify(recovery, never()).recoverClient("healthy-client");
        clock.set(firstCutoff.plusSeconds(10));
        assertThat(recovery.recoverExpiredTasks()).isEqualTo(1);
        Instant nextCutoff = firstCutoff.plusSeconds(20);
        clock.set(nextCutoff);
        assertThat(recovery.recoverExpiredTasks()).isZero();
        verify(recovery).recoverClient("healthy-client");
        verify(recovery, times(2)).recoverClient("broken-0");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(4)).queryForList(sql.capture(), args.capture());
        assertPageQuery(sql.getAllValues().get(0), args.getAllValues().get(0), firstCutoff, null);
        assertPageQuery(sql.getAllValues().get(1), args.getAllValues().get(1), firstCutoff, broken.getLast());
        assertPageQuery(sql.getAllValues().get(2), args.getAllValues().get(2), firstCutoff, healthy);
        assertPageQuery(sql.getAllValues().get(3), args.getAllValues().get(3), nextCutoff, null);
    }

    @Test
    void emptyFirstPageClearsTheCutoffBeforeLaterWorkExpires() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiTaskRecoveryService recovery = spy(new AiTaskRecoveryService(jdbc, new ObjectMapper(), mock(TransactionTemplate.class)));
        Instant firstCutoff = Instant.parse("2026-01-02T00:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(firstCutoff);
        doAnswer(invocation -> clock.get()).when(recovery).databaseNow();
        Map<String, Object> later = Map.of("id", new UUID(0, 1).toString(), "client_id", "later-client",
                "expires_at", Timestamp.from(firstCutoff.plusSeconds(1)));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(), List.of(later));
        doReturn(1).when(recovery).recoverClient("later-client");

        assertThat(recovery.recoverExpiredTasks()).isZero();
        Instant nextCutoff = firstCutoff.plusSeconds(10);
        clock.set(nextCutoff);
        assertThat(recovery.recoverExpiredTasks()).isEqualTo(1);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).queryForList(sql.capture(), args.capture());
        assertPageQuery(sql.getAllValues().get(0), args.getAllValues().get(0), firstCutoff, null);
        assertPageQuery(sql.getAllValues().get(1), args.getAllValues().get(1), nextCutoff, null);
    }

    private void assertPageQuery(String sql, Object[] args, Instant cutoff, Map<String, Object> cursor) {
        String predicate = cursor == null ? "" : " and (expires_at>? or (expires_at=? and id>?))";
        assertThat(sql.replaceAll("\\s+", " ").trim()).isEqualTo(
                "select id,client_id,expires_at from ai_task_ledger where status='PROCESSING' and expires_at<=?"
                        + predicate + " order by expires_at,id limit 100");
        Object[] expected = cursor == null ? new Object[]{cutoff} : new Object[]{cutoff,
                ((Timestamp) cursor.get("expires_at")).toInstant(), ((Timestamp) cursor.get("expires_at")).toInstant(), cursor.get("id")};
        assertThat(args).containsExactly(expected);
    }

    private Map<String, Object> candidate(String id, String client) {
        return Map.of("id", id, "client_id", client, "expires_at", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
    }
}
