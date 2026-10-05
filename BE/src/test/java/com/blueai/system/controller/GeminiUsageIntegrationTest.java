package com.blueai.system.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.system.service.GeminiUsageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = BlueAiAuthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeminiUsageIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired GeminiUsageService usageService;

    @BeforeEach
    @AfterEach
    void cleanUsage() {
        jdbcTemplate.update("DELETE FROM gemini_usage_events");
    }

    @Test
    void usageReportIsAggregatedAndAdminOnly() throws Exception {
        usageService.record("gemini-test", 120, 30, 160);
        usageService.record("gemini-test", 80, 20, 105);

        mockMvc.perform(get("/api/v1/system/gemini-usage").with(user("viewer").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/system/gemini-usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifetime.requests").value(2))
                .andExpect(jsonPath("$.lifetime.promptTokens").value(200))
                .andExpect(jsonPath("$.lifetime.completionTokens").value(50))
                .andExpect(jsonPath("$.lifetime.totalTokens").value(265))
                .andExpect(jsonPath("$.recent.length()").value(2))
                .andExpect(jsonPath("$.recent[0].model").value("gemini-test"));
    }

    @Test
    void emptyDatabaseReturnsZerosAndEmptyLists() throws Exception {
        mockMvc.perform(get("/api/v1/system/gemini-usage").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.last24Hours.requests").value(0))
                .andExpect(jsonPath("$.last7Days.totalTokens").value(0))
                .andExpect(jsonPath("$.lifetime.promptTokens").value(0))
                .andExpect(jsonPath("$.lifetime.completionTokens").value(0))
                .andExpect(jsonPath("$.daily.length()").value(0))
                .andExpect(jsonPath("$.recent.length()").value(0))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());
    }

    @Test
    void anonymousRequestRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/system/gemini-usage"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"-1,0,0", "0,-1,0", "0,0,-1"})
    void rejectsNegativeProviderCounts(long prompt, long completion, long total) {
        usageService.record("gemini-test", prompt, completion, total);
        assertThat(usageService.report().lifetime().requests()).isZero();
    }

    @Test
    void zeroUsageStillCountsAReportedRequest() {
        usageService.record("gemini-test", 0, 0, 0);
        assertThat(usageService.report().lifetime().requests()).isEqualTo(1);
        assertThat(usageService.report().lifetime().totalTokens()).isZero();
    }

    @Test
    void unknownAndLongModelNamesAreStoredSafely() {
        usageService.record(null, 1, 1, 2);
        usageService.record("g".repeat(200), 1, 1, 2);
        assertThat(usageService.report().recent()).extracting(event -> event.model())
                .containsExactlyInAnyOrder("unknown", "g".repeat(120));
    }

    @Test
    void timeWindowsExcludeOlderCallsButLifetimeRetainsThem() {
        Instant now = Instant.now();
        insertAt(now.minus(Duration.ofHours(2)), "recent", 10, 2, 15);
        insertAt(now.minus(Duration.ofHours(48)), "this-week", 20, 4, 30);
        insertAt(now.minus(Duration.ofDays(8)), "older", 30, 6, 45);
        var report = usageService.report();
        assertThat(report.last24Hours().requests()).isEqualTo(1);
        assertThat(report.last24Hours().totalTokens()).isEqualTo(15);
        assertThat(report.last7Days().requests()).isEqualTo(2);
        assertThat(report.last7Days().promptTokens()).isEqualTo(30);
        assertThat(report.last7Days().completionTokens()).isEqualTo(6);
        assertThat(report.last7Days().totalTokens()).isEqualTo(45);
        assertThat(report.lifetime().requests()).isEqualTo(3);
        assertThat(report.lifetime().totalTokens()).isEqualTo(90);
        assertThat(report.daily()).hasSize(2);
    }

    @Test
    void dailyUsageGroupsAllCallsAndSortsDaysChronologically() {
        Instant day = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(Duration.ofDays(2));
        insertAt(day.plus(Duration.ofHours(2)), "a", 10, 2, 12);
        insertAt(day.plus(Duration.ofHours(3)), "b", 20, 3, 23);
        insertAt(day.minus(Duration.ofDays(1)), "c", 30, 4, 34);
        var daily = usageService.report().daily();
        assertThat(daily).hasSize(2);
        assertThat(daily.get(0).date()).isBefore(daily.get(1).date());
        assertThat(daily.get(1).requests()).isEqualTo(2);
        assertThat(daily.get(1).promptTokens()).isEqualTo(30);
        assertThat(daily.get(1).completionTokens()).isEqualTo(5);
        assertThat(daily.get(1).totalTokens()).isEqualTo(35);
    }

    @Test
    void recentListReturnsNewestFiftyWithoutLimitingTotals() {
        Instant base = Instant.now().minus(Duration.ofHours(1));
        for (int i = 0; i < 60; i++) {
            insertAt(base.plusSeconds(i), "model-" + i, 10, 2, 12);
        }
        var report = usageService.report();
        assertThat(report.recent()).hasSize(50);
        assertThat(report.recent().getFirst().model()).isEqualTo("model-59");
        assertThat(report.recent().getLast().model()).isEqualTo("model-10");
        assertThat(report.lifetime().requests()).isEqualTo(60);
        assertThat(report.lifetime().totalTokens()).isEqualTo(720);
    }

    @Test
    void nextApiRequestReflectsANewPromptImmediately() throws Exception {
        mockMvc.perform(get("/api/v1/system/gemini-usage").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.lifetime.requests").value(0));
        usageService.record("gemini-test", 884, 9, 893);
        mockMvc.perform(get("/api/v1/system/gemini-usage").with(user("admin").roles("ADMIN")))
                .andExpect(jsonPath("$.lifetime.requests").value(1))
                .andExpect(jsonPath("$.lifetime.promptTokens").value(884))
                .andExpect(jsonPath("$.lifetime.totalTokens").value(893));
    }

    @Test
    void largeTokenCountsDoNotOverflowIntegerTotals() {
        usageService.record("gemini-test", 5_000_000_000L, 4_000_000_000L, 9_000_000_000L);
        var report = usageService.report();
        assertThat(report.lifetime().totalTokens()).isEqualTo(9_000_000_000L);
        assertThat(report.daily().getFirst().promptTokens()).isEqualTo(5_000_000_000L);
        assertThat(report.recent().getFirst().completionTokens()).isEqualTo(4_000_000_000L);
    }

    @Test
    void concurrentUsersDoNotLoseOrDuplicateUsageEvents() throws Exception {
        try (var executor = Executors.newFixedThreadPool(6)) {
            var calls = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 30; i++) {
                int userId = i;
                calls.add(() -> {
                    usageService.record("user-model-" + userId, 10, 2, 12);
                    return null;
                });
            }
            for (var result : executor.invokeAll(calls)) result.get();
        }
        var report = usageService.report();
        assertThat(report.lifetime().requests()).isEqualTo(30);
        assertThat(report.lifetime().promptTokens()).isEqualTo(300);
        assertThat(report.lifetime().completionTokens()).isEqualTo(60);
        assertThat(report.lifetime().totalTokens()).isEqualTo(360);
        assertThat(report.recent()).extracting(event -> event.id()).doesNotHaveDuplicates();
    }

    private void insertAt(Instant time, String model, long prompt, long completion, long total) {
        jdbcTemplate.update("""
                INSERT INTO gemini_usage_events
                    (id, model, prompt_tokens, completion_tokens, total_tokens, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), model, prompt, completion, total, Timestamp.from(time));
    }
}
