package com.blueai.auth.service;

import com.blueai.auth.dto.LoginRequest;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.repository.AuthAuditLogRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** A one-connection pool makes every nested REQUIRES_NEW audit fail deterministically. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-audit-capacity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        // Flyway uses two connections at startup; constrain only the auth runtime.
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=250"
})
@ActiveProfiles("test")
class AuthAuditCapacityIntegrationTest {
    @Autowired AuthService authService;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired AuthAuditLogRepository audits;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired HikariDataSource dataSource;

    @BeforeEach
    void cleanOwnDatabase() {
        dataSource.getHikariConfigMXBean().setMaximumPoolSize(1);
        dataSource.getHikariPoolMXBean().softEvictConnections();
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(1);
        tokens.deleteAll();
        audits.deleteAll();
        users.deleteAll();
    }

    @Test
    void reuseRevocationAndAuditCommitWithOnlyOneAvailableConnection() {
        User user = users.saveAndFlush(new User("capacity@example.test", encoder.encode("Password123!"), "Capacity probe"));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var initial = authService.login(new LoginRequest(user.getEmail(), "Password123!"));
            var rotated = authService.refresh(initial.refreshToken());
            assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                    .isInstanceOfSatisfying(ApiException.class, error ->
                            assertThat(error.getCode()).isEqualTo("REFRESH_TOKEN_REUSED"));
            assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
            assertThat(audits.findAll()).anyMatch(log -> "REFRESH_TOKEN_REUSE".equals(log.getAction()));
            assertThatThrownBy(() -> authService.refresh(rotated.refreshToken())).isInstanceOf(ApiException.class);
        });
    }

    @Test
    void loginFailureAndExpiredRefreshAreAuditedWithoutNestedConnections() {
        User user = users.saveAndFlush(new User("failure@example.test", encoder.encode("Password123!"), "Failure probe"));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "Incorrect123!")))
                    .isInstanceOf(ApiException.class);
            var session = authService.login(new LoginRequest(user.getEmail(), "Password123!"));
            jdbc.update("UPDATE refresh_tokens SET expires_at = TIMESTAMP '2000-01-01 00:00:00'");
            assertThatThrownBy(() -> authService.refresh(session.refreshToken()))
                    .isInstanceOfSatisfying(ApiException.class, error ->
                            assertThat(error.getCode()).isEqualTo("REFRESH_TOKEN_EXPIRED"));
            assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
            assertThat(audits.findAll()).anyMatch(log -> "LOGIN".equals(log.getAction()) && "FAILURE".equals(log.getOutcome()))
                    .anyMatch(log -> "REFRESH".equals(log.getAction()) && "FAILURE".equals(log.getOutcome()));
        });
    }

    @Test
    void unknownAndRepeatedLogoutCannotGrowAuditHistory() {
        for (int i = 0; i < 100; i++) authService.logout("invalid-" + i);
        assertThat(audits.count()).isZero();
        User user = users.saveAndFlush(new User("logout@example.test", encoder.encode("Password123!"), "Logout probe"));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var session = authService.login(new LoginRequest(user.getEmail(), "Password123!"));
            authService.logout(session.refreshToken());
            authService.logout(session.refreshToken());
            assertThat(tokens.findAll()).allMatch(token -> token.getRevokedAt() != null);
            assertThat(audits.findAll().stream().filter(log -> "LOGOUT".equals(log.getAction())))
                    .hasSize(1);
        });
    }
}
