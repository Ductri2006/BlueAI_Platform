package com.blueai.quota;

import com.blueai.auth.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class AiTokenQuotaServiceTest {
    JdbcTemplate jdbc;
    AiTokenQuotaService quota;
    AiQuotaProperties properties;
    AtomicReference<Instant> now;

    @BeforeEach void setup() {
        var source = source();
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V15__ai_token_quota.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        properties = new AiQuotaProperties();
        properties.setEnabled(true);
        properties.setFiveHourTokens(1000);
        properties.setWeeklyTokens(3000);
        properties.setChatRpm(1000);
        properties.setChatRpd(10000);
        now = new AtomicReference<>(Instant.parse("2026-11-17T03:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        quota = new AiTokenQuotaService(jdbc, properties, new DataSourceTransactionManager(source), clock);
    }

    DriverManagerDataSource source() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    AiTokenQuotaService.Reservation reserve(String actor, long input, int output) {
        try (var ignored = AiUsageActor.forOwner(actor)) { return quota.reserve("COMPLETION", input, output); }
    }

    @Test void snapshotDoesNotStartWindowsAndActualUsageIsSettledOnlyOnce() {
        assertThat(quota.snapshot("one").fiveHour().resetsAt()).isNull();
        var request = reserve("one", 200, 100);
        assertThat(quota.snapshot("one").fiveHour().reserved()).isEqualTo(300);
        quota.confirm(request, 50, 20, 90); // Includes thinking tokens in provider total.
        quota.confirm(request, 50, 20, 90);
        var view = quota.snapshot("one");
        assertThat(view.fiveHour().used()).isEqualTo(90);
        assertThat(view.weekly().used()).isEqualTo(90);
        assertThat(view.fiveHour().reserved()).isZero();
        assertThat(view.fiveHour().remaining()).isEqualTo(910);
        assertThat(quota.snapshot("two").fiveHour().used()).isZero();
    }

    @Test void bothBudgetsMustAdmitAndReleaseRefundsUnusedCapacity() {
        var one = reserve("one", 800, 100);
        assertThatThrownBy(() -> reserve("one", 101, 0)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("AI_TOKEN_QUOTA_EXHAUSTED"));
        quota.release(one);
        assertThat(quota.snapshot("one").fiveHour().remaining()).isEqualTo(1000);
        properties.setWeeklyTokens(50);
        assertThatThrownBy(() -> reserve("one", 51, 0)).isInstanceOf(ApiException.class);
    }

    @Test void fiveHourAndSevenDayWindowsResetIndependentlyAndLateSettlementStaysInOriginalWindow() {
        var pending = reserve("one", 200, 0);
        quota.confirm(reserve("one", 100, 0), 100, 0, 100);
        now.set(now.get().plus(Duration.ofHours(5)));
        // The expired pending request becomes an estimate in its original weekly window.
        var view = quota.snapshot("one");
        assertThat(view.fiveHour().used()).isZero();
        assertThat(view.fiveHour().resetsAt()).isNull();
        assertThat(view.weekly().used()).isEqualTo(300);
        quota.confirm(pending, 30, 0, 30);
        assertThat(quota.snapshot("one").fiveHour().used()).isZero();
        assertThat(quota.snapshot("one").weekly().used()).isEqualTo(130);
        assertThat(quota.snapshot("one").weekly().uncertain()).isZero();
        now.set(now.get().plus(Duration.ofDays(7)));
        assertThat(quota.snapshot("one").weekly().used()).isZero();
    }

    @Test void ambiguousFailureHasBoundedHoldAndExplicitEstimate() {
        reserve("one", 300, 100);
        now.set(now.get().plusSeconds(301));
        var view = quota.snapshot("one");
        assertThat(view.fiveHour().reserved()).isZero();
        assertThat(view.fiveHour().used()).isEqualTo(400);
        assertThat(view.fiveHour().uncertain()).isEqualTo(400);
        quota.reconcileExpired();
        assertThat(quota.snapshot("one").fiveHour().used()).isEqualTo(400);
    }

    @Test void concurrentTabsCannotOverbookAnAccount() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) calls.add(() -> {
                try { reserve("one", 100, 0); return true; }
                catch (ApiException exhausted) { return false; }
            });
            var results = executor.invokeAll(calls);
            int admitted = 0;
            for (var result : results) if (result.get()) admitted++;
            assertThat(admitted).isEqualTo(10);
            assertThat(quota.snapshot("one").fiveHour().reserved()).isEqualTo(1000);
        }
    }

    @Test void globalProjectLimitIsSharedAcrossUsersAndRejectsWithoutStartingTheirWindow() {
        properties.setChatRpm(1);
        quota.confirm(reserve("one", 20, 0), 20, 0, 20);
        assertThatThrownBy(() -> reserve("two", 20, 0)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("AI_PROJECT_RATE_LIMITED"));
        assertThat(quota.snapshot("two").fiveHour().resetsAt()).isNull();
        now.set(now.get().plusSeconds(60));
        assertThat(reserve("two", 20, 0)).isNotNull();
    }

    @Test void systemIndexingDoesNotConsumeUserAllocationAndModelsHaveSeparateCounters() {
        properties.setChatRpm(1);
        reserve("one", 20, 0);
        var system = quota.reserve("EMBEDDING", 20, 0);
        quota.confirm(system, 10, 0, 10);
        assertThat(quota.snapshot("one").fiveHour().reserved()).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_token_operations WHERE user_id IS NULL", Integer.class)).isEqualTo(1);
    }

    @Test void countedEmbeddingInputIsRecordedWithItsMeasurementSource() {
        var request = reserve("one", 100, 0);
        quota.confirmCountedInput(request, 6);
        quota.confirmCountedInput(request, 6);
        assertThat(quota.snapshot("one").fiveHour().used()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT measurement FROM ai_token_operations WHERE id=?", String.class, request.id())).isEqualTo("COUNT_TOKENS");
    }

    @Test void projectDailyLimitResetsAtPacificMidnightWithoutRefillingTheUserBudget() {
        properties.setChatRpd(1);
        now.set(Instant.parse("2026-11-17T07:59:59Z"));
        quota.confirm(reserve("one", 10, 0), 10, 0, 10);
        assertThatThrownBy(() -> reserve("two", 10, 0)).isInstanceOf(ApiException.class);
        now.set(Instant.parse("2026-11-17T08:00:00Z"));
        quota.confirm(reserve("one", 10, 0), 10, 0, 10);
        assertThat(quota.snapshot("one").fiveHour().used()).isEqualTo(20);
    }
}
