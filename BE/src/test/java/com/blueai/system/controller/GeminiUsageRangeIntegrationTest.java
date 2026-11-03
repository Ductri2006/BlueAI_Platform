package com.blueai.system.controller;

import com.blueai.BlueAiAuthApplication;
import com.blueai.system.service.GeminiUsageRangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BlueAiAuthApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:usage-range;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeminiUsageRangeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GeminiUsageRangeService reports;
    private final LocalDate start = LocalDate.of(2026, 11, 1);

    @BeforeEach void clear() { jdbc.update("DELETE FROM gemini_usage_events"); }

    @Test void includesBothLocalDateBoundariesAndGroupsByVietnamDay() throws Exception {
        insert("2026-10-31T16:59:59Z", "gemini-3.5-flash-lite", 100, 10, 110);
        insert("2026-10-31T17:00:00Z", "gemini-3.5-flash-lite", 100, 10, 110);
        insert("2026-11-01T16:59:59Z", "gemini-3.5-flash-lite", 100, 10, 110);
        insert("2026-11-01T17:00:00Z", "gemini-3.5-flash-lite", 100, 10, 110);
        insert("2026-11-02T16:59:59.999999Z", "gemini-3.5-flash-lite", 100, 10, 110);
        insert("2026-11-02T17:00:00Z", "gemini-3.5-flash-lite", 100, 10, 110);
        mvc.perform(get("/api/v1/system/gemini-usage").param("startDate", "2026-11-01").param("endDate", "2026-11-02")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.totals.requests").value(4)).andExpect(jsonPath("$.recent.length()").value(4))
                .andExpect(jsonPath("$.daily[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.daily[0].requests").value(2))
                .andExpect(jsonPath("$.daily[1].requests").value(2));
    }

    @Test void pricesEachModelAndThinkingTokensAndNeverTreatsUnknownAsFree() {
        insert("2026-11-01T00:00:00Z", "gemini-3.5-flash-lite", 1_000_000, 100_000, 1_500_000);
        insert("2026-11-01T00:01:00Z", "gemini-2.5-pro", 200_001, 100_000, 300_001);
        insert("2026-11-01T00:02:00Z", "unknown-model", 1_000_000, 100_000, 1_100_000);
        var report = reports.report(start, start, 1, 25);
        // Flash: 0.30 + 500k * 2.50/M = 1.55. Pro long context: 0.5000025 + 1.50.
        assertThat(report.totals().cost().inputUsd()).isEqualByComparingTo("0.8000025");
        assertThat(report.totals().cost().outputUsd()).isEqualByComparingTo("2.75");
        assertThat(report.totals().cost().totalUsd()).isEqualByComparingTo("3.5500025");
        assertThat(report.totals().cost().unpricedRequests()).isEqualTo(1);
        assertThat(report.models()).hasSize(3);
        assertThat(report.recent().getFirst().cost().unpricedRequests()).isEqualTo(1);
    }

    @Test void paginatesBeyondFiftyWithoutChangingTotalsOrRepeatingRows() {
        for (int i = 0; i < 63; i++) insert(Instant.parse("2026-11-01T00:00:00Z").plusSeconds(i).toString(),
                "gemini-3.5-flash-lite", 100, 10, 110);
        var first = reports.report(start, start, 1, 25);
        var second = reports.report(start, start, 2, 25);
        var third = reports.report(start, start, 3, 25);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.recent()).hasSize(25);
        assertThat(second.recent()).hasSize(25);
        assertThat(third.recent()).hasSize(13);
        assertThat(first.totals()).isEqualTo(second.totals()).isEqualTo(third.totals());
        assertThat(first.totals().requests()).isEqualTo(63);
        assertThat(first.recent()).extracting(e -> e.id()).doesNotContainAnyElementsOf(second.recent().stream().map(e -> e.id()).toList());
        assertThat(reports.report(start, start, 999, 25).page()).isEqualTo(3);
    }

    @Test void zeroFillsEveryDayOfAnEmptyLeapMonth() {
        var report = reports.report(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29), 1, 10);
        assertThat(report.daily()).hasSize(29);
        assertThat(report.daily()).allSatisfy(day -> assertThat(day.requests()).isZero());
        assertThat(report.totals().requests()).isZero();
        assertThat(report.totals().cost().totalUsd()).isZero();
        assertThat(report.recent()).isEmpty();
        assertThat(report.totalPages()).isEqualTo(1);
    }

    @Test void invalidFiltersAreRejectedAndReportRemainsAdminOnly() throws Exception {
        for (String[] range : new String[][]{{"2026-11-02", "2026-11-01"}, {"2025-01-01", "2026-11-01"},
                {"2026-02-30", "2026-03-01"}}) {
            mvc.perform(get("/api/v1/system/gemini-usage").param("startDate", range[0]).param("endDate", range[1])
                    .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/system/gemini-usage").param("startDate", "2026-11-01")
                .with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        for (String parameter : new String[]{"page", "pageSize"}) {
            mvc.perform(get("/api/v1/system/gemini-usage").param("startDate", "2026-11-01").param("endDate", "2026-11-01")
                    .param(parameter, "0").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/system/gemini-usage").param("startDate", "2026-11-01").param("endDate", "2026-11-01")
                .with(user("viewer").roles("USER"))).andExpect(status().isForbidden());
    }

    private void insert(String time, String model, long prompt, long completion, long total) {
        jdbc.update("INSERT INTO gemini_usage_events(id,model,prompt_tokens,completion_tokens,total_tokens,recorded_at) VALUES(?,?,?,?,?,?)", ps -> {
            ps.setString(1, UUID.randomUUID().toString()); ps.setString(2, model);
            ps.setLong(3, prompt); ps.setLong(4, completion); ps.setLong(5, total);
            ps.setTimestamp(6, Timestamp.from(Instant.parse(time)), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
        });
    }
}
