package com.blueai.auth.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.PasswordResetToken;
import com.blueai.auth.model.RefreshToken;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MySQL current reads must replace preloaded JPA snapshots before admin mutations. */
@SpringBootTest(properties = {
        "app.auth.retention.scheduled-enabled=false",
        "app.ai.tasks.recovery-enabled=false"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MYSQL_AUTH_TEST_URL", matches = ".+")
class AuthAdminMutationMySqlIntegrationTest {

    private static final String OLD_PASSWORD = "OldPassword123!";
    private static final String NEW_PASSWORD = "NewPassword123!";
    private static final String SECOND_PASSWORD = "SecondPassword123!";

    @Autowired AdminUserService adminUserService;
    @Autowired EntityManager entityManager;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private User administrator;
    private User target;
    private boolean disposableDatabaseVerified;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        String url = System.getenv("MYSQL_AUTH_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/blueai_auth_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Admin mutation tests require a disposable blueai_auth_test database.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> System.getenv("MYSQL_AUTH_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv("MYSQL_AUTH_TEST_PASSWORD"));
        registry.add("spring.flyway.placeholders.text_type", () -> "LONGTEXT");
        registry.add("spring.flyway.placeholders.long_text_type", () -> "LONGTEXT");
        registry.add("spring.flyway.placeholders.idempotency_key_type",
                () -> "VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
    }

    @BeforeEach
    void createOwnFixtures() {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo("blueai_auth_test");
        disposableDatabaseVerified = true;
        String suffix = UUID.randomUUID().toString();
        administrator = new User("admin-mutation-admin-" + suffix + "@example.test",
                passwordEncoder.encode(OLD_PASSWORD), "Admin Mutation Test", UserRole.ADMIN);
        target = new User("admin-mutation-user-" + suffix + "@example.test",
                passwordEncoder.encode(OLD_PASSWORD), "Admin Mutation Test", UserRole.USER);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.persist(administrator);
            entityManager.persist(target);
            Instant expiresAt = Instant.now().plusSeconds(3600);
            for (int index = 0; index < 2; index++) {
                entityManager.persist(new RefreshToken(target, tokenHash(), UUID.randomUUID().toString(), expiresAt));
            }
            entityManager.persist(new PasswordResetToken(target, tokenHash(), expiresAt));
        });
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        if (!disposableDatabaseVerified) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (User fixture : new User[]{target, administrator}) {
                if (fixture == null) continue;
                String id = fixture.getId();
                jdbc.update("delete from password_reset_tokens where user_id = ?", id);
                jdbc.update("delete from refresh_tokens where user_id = ?", id);
                jdbc.update("delete from auth_audit_logs where actor_user_id = ? or target_user_id = ?", id, id);
                jdbc.update("delete from app_users where id = ?", id);
            }
        });
    }

    @Test
    void disablingPreloadedUserPreservesConcurrentPasswordAndAuthVersion() throws Exception {
        assertThat(withPreloadedUser(
                () -> setPassword(NEW_PASSWORD),
                () -> changeStatus(UserStatus.DISABLED))).isNull();

        User current = currentUser();
        assertThat(current.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(current.getAuthVersion()).isEqualTo(2);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, current.getPasswordHash())).isTrue();
        assertRefreshTokensRevoked();
        assertResetTokensRemoved();
    }

    @Test
    void deletingPreloadedUserPreservesConcurrentPasswordAndAuthVersion() throws Exception {
        assertThat(withPreloadedUser(() -> setPassword(NEW_PASSWORD), this::softDelete)).isNull();

        User current = currentUser();
        assertThat(current.isDeleted()).isTrue();
        assertThat(current.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(current.getAuthVersion()).isEqualTo(2);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, current.getPasswordHash())).isTrue();
        assertRefreshTokensRevoked();
        assertResetTokensRemoved();
    }

    @Test
    void reactivatingPreloadedUserCannotResurrectConcurrentTombstone() throws Exception {
        changeStatus(UserStatus.DISABLED);
        ApiException failure = withPreloadedUser(this::softDelete, () -> changeStatus(UserStatus.ACTIVE));

        assertConflict(failure, "USER_DELETED");
        User current = currentUser();
        assertThat(current.isDeleted()).isTrue();
        assertThat(current.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(current.getAuthVersion()).isEqualTo(2);
        assertThat(current.getPasswordHash()).isEqualTo(target.getPasswordHash());
        assertRefreshTokensRevoked();
    }

    @Test
    void settingPasswordRefreshesPreloadedAuthVersionAfterConcurrentPasswordChange() throws Exception {
        assertThat(withPreloadedUser(
                () -> setPassword(NEW_PASSWORD),
                () -> setPassword(SECOND_PASSWORD))).isNull();

        User current = currentUser();
        assertThat(current.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(current.isDeleted()).isFalse();
        assertThat(current.getAuthVersion()).isEqualTo(2);
        assertThat(passwordEncoder.matches(SECOND_PASSWORD, current.getPasswordHash())).isTrue();
        assertRefreshTokensRevoked();
        assertResetTokensRemoved();
    }

    @ParameterizedTest
    @EnumSource(Invalidation.class)
    void settingPasswordRejectsPreloadedUserInvalidatedConcurrently(Invalidation invalidation) throws Exception {
        ApiException failure = withPreloadedUser(
                () -> invalidate(invalidation),
                () -> setPassword(NEW_PASSWORD));

        assertConflict(failure, "USER_NOT_ACTIVE");
        User current = currentUser();
        assertThat(current.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(current.isDeleted()).isEqualTo(invalidation == Invalidation.DELETE);
        assertThat(current.getAuthVersion()).isEqualTo(1);
        assertThat(current.getPasswordHash()).isEqualTo(target.getPasswordHash());
        assertRefreshTokensRevoked();
        assertThat(resetTokenCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void everyAdminMutatorHoldsUserLockThroughRevocationAndCommit(Mutation mutation) throws Exception {
        if (mutation == Mutation.REACTIVATE) changeStatus(UserStatus.DISABLED);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            User preloaded = entityManager.find(User.class, target.getId());
            switch (mutation) {
                case PASSWORD -> setPassword(NEW_PASSWORD);
                case DISABLE -> changeStatus(UserStatus.DISABLED);
                case REACTIVATE -> changeStatus(UserStatus.ACTIVE);
                case DELETE -> softDelete();
            }
            if (mutation != Mutation.REACTIVATE) {
                assertRefreshTokensRevoked();
                assertThat(entityManager.contains(preloaded))
                        .as("bulk revocation cleared the persistence context, not the database lock")
                        .isFalse();
            }
            if (mutation == Mutation.PASSWORD) assertResetTokensRemoved();
            // NOWAIT distinguishes an actual held row lock from an unscheduled competing thread.
            assertThatThrownBy(this::lockUserFromSeparateConnection)
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(3572));
        });
        lockUserFromSeparateConnection();
    }

    @ParameterizedTest
    @EnumSource(PasswordFailure.class)
    void passwordValidationFailureCommitsOnlyFailureAudit(PasswordFailure failure) {
        if (failure == PasswordFailure.DISABLED) changeStatus(UserStatus.DISABLED);
        if (failure == PasswordFailure.DELETED) softDelete();
        User before = currentUser();
        Integer activeTokensBefore = activeRefreshTokenCount();
        Integer resetTokensBefore = resetTokenCount();
        String userId = switch (failure) {
            case INVALID_ID -> "not-a-user-id";
            case MISSING_USER -> UUID.randomUUID().toString();
            default -> target.getId();
        };
        String password = failure == PasswordFailure.TOO_LONG ? "é".repeat(40) : NEW_PASSWORD;
        String code = switch (failure) {
            case INVALID_ID -> "INVALID_USER_ID";
            case MISSING_USER -> "USER_NOT_FOUND";
            case DISABLED, DELETED -> "USER_NOT_ACTIVE";
            case TOO_LONG -> "PASSWORD_TOO_LONG";
        };
        HttpStatus expectedStatus = switch (failure) {
            case INVALID_ID, TOO_LONG -> HttpStatus.BAD_REQUEST;
            case MISSING_USER -> HttpStatus.NOT_FOUND;
            case DISABLED, DELETED -> HttpStatus.CONFLICT;
        };

        // No outer transaction: the service must commit its failure audit before rethrowing.
        assertThatThrownBy(() -> adminUserService.setPassword(administrator.getId(), userId, password))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getStatus()).isEqualTo(expectedStatus);
                });

        assertUserUnchanged(before);
        assertThat(activeRefreshTokenCount()).isEqualTo(activeTokensBefore);
        assertThat(resetTokenCount()).isEqualTo(resetTokensBefore);
        assertThat(auditCount("ADMIN_PASSWORD_SET", "FAILURE")).isEqualTo(1);
        assertThat(auditCount("ADMIN_PASSWORD_SET", "SUCCESS")).isZero();
        assertThat(jdbc.queryForObject(
                "select target_user_id from auth_audit_logs where actor_user_id = ? and action = ? and outcome = ?",
                String.class, administrator.getId(), "ADMIN_PASSWORD_SET", "FAILURE"))
                .isEqualTo(failure == PasswordFailure.INVALID_ID ? null : userId);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void successfulAdminMutationAuditIsVisibleBeforeCommit(Mutation mutation) {
        if (mutation == Mutation.REACTIVATE) changeStatus(UserStatus.DISABLED);
        String action = auditAction(mutation);
        int auditsBefore = auditCount(action, "SUCCESS");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            mutate(mutation);
            entityManager.flush();
            assertThat(auditCount(action, "SUCCESS")).isEqualTo(auditsBefore + 1);
        });

        assertThat(auditCount(action, "SUCCESS")).isEqualTo(auditsBefore + 1);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void runtimeFailureRollsBackAdminMutationAndSuccessAudit(Mutation mutation) {
        if (mutation == Mutation.REACTIVATE) changeStatus(UserStatus.DISABLED);
        User before = currentUser();
        Integer activeTokensBefore = activeRefreshTokenCount();
        Integer resetTokensBefore = resetTokenCount();
        String action = auditAction(mutation);
        int auditsBefore = auditCount(action, "SUCCESS");

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            mutate(mutation);
            entityManager.flush();
            assertThat(auditCount(action, "SUCCESS")).isEqualTo(auditsBefore + 1);
            throw new IllegalStateException("forced admin transaction rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("forced admin transaction rollback");

        assertUserUnchanged(before);
        assertThat(activeRefreshTokenCount()).isEqualTo(activeTokensBefore);
        assertThat(resetTokenCount()).isEqualTo(resetTokensBefore);
        assertThat(auditCount(action, "SUCCESS")).isEqualTo(auditsBefore);
    }

    @Test
    void explicitRollbackDiscardsPasswordMutationAndSuccessAudit() {
        User before = currentUser();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            setPassword(NEW_PASSWORD);
            entityManager.flush();
            assertThat(auditCount("ADMIN_PASSWORD_SET", "SUCCESS")).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertUserUnchanged(before);
        assertThat(activeRefreshTokenCount()).isEqualTo(2);
        assertThat(resetTokenCount()).isEqualTo(1);
        assertThat(auditCount("ADMIN_PASSWORD_SET", "SUCCESS")).isZero();
    }

    private void assertUserUnchanged(User before) {
        User current = currentUser();
        assertThat(current.getPasswordHash()).isEqualTo(before.getPasswordHash());
        assertThat(current.getStatus()).isEqualTo(before.getStatus());
        assertThat(current.getDeletedAt()).isEqualTo(before.getDeletedAt());
        assertThat(current.getAuthVersion()).isEqualTo(before.getAuthVersion());
    }

    private Integer activeRefreshTokenCount() {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, target.getId());
    }

    private Integer auditCount(String action, String outcome) {
        return jdbc.queryForObject("select count(*) from auth_audit_logs where actor_user_id = ? and action = ? and outcome = ?",
                Integer.class, administrator.getId(), action, outcome);
    }

    private void mutate(Mutation mutation) {
        switch (mutation) {
            case PASSWORD -> setPassword(NEW_PASSWORD);
            case DISABLE -> changeStatus(UserStatus.DISABLED);
            case REACTIVATE -> changeStatus(UserStatus.ACTIVE);
            case DELETE -> softDelete();
        }
    }

    private static String auditAction(Mutation mutation) {
        return switch (mutation) {
            case PASSWORD -> "ADMIN_PASSWORD_SET";
            case DISABLE, REACTIVATE -> "ADMIN_USER_STATUS_CHANGED";
            case DELETE -> "ADMIN_USER_DELETED";
        };
    }

    private ApiException withPreloadedUser(Runnable committedMutation, Runnable preloadedMutation) throws Exception {
        CountDownLatch preloaded = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ApiException> pending = executor.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        User stale = entityManager.find(User.class, target.getId());
                        assertThat(stale).isNotNull();
                        long preloadedVersion = stale.getAuthVersion();
                        preloaded.countDown();
                        await(resume);
                        assertThat(entityManager.contains(stale)).isTrue();
                        assertThat(stale.getAuthVersion()).isEqualTo(preloadedVersion);
                        preloadedMutation.run();
                    });
                    return null;
                } catch (ApiException exception) {
                    return exception;
                }
            });
            assertThat(preloaded.await(10, TimeUnit.SECONDS)).as("worker preloaded the old user snapshot").isTrue();
            committedMutation.run(); // Separate transaction commits before the stale worker resumes.
            resume.countDown();
            return pending.get(10, TimeUnit.SECONDS);
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void lockUserFromSeparateConnection() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from app_users where id = ? for update nowait")) {
                statement.setString(1, target.getId());
                try (ResultSet row = statement.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo(target.getId());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private User currentUser() {
        return new TransactionTemplate(transactionManager).execute(status -> entityManager.find(User.class, target.getId()));
    }

    private void assertRefreshTokensRevoked() {
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ?", Integer.class, target.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, target.getId())).isZero();
    }

    private void assertResetTokensRemoved() {
        assertThat(resetTokenCount()).isZero();
    }

    private Integer resetTokenCount() {
        return jdbc.queryForObject("select count(*) from password_reset_tokens where user_id = ?", Integer.class, target.getId());
    }

    private void setPassword(String password) {
        adminUserService.setPassword(administrator.getId(), target.getId(), password);
    }

    private void changeStatus(UserStatus status) {
        adminUserService.updateStatus(administrator.getId(), target.getId(), status);
    }

    private void softDelete() {
        adminUserService.softDelete(administrator.getId(), target.getId());
    }

    private void invalidate(Invalidation invalidation) {
        if (invalidation == Invalidation.DELETE) softDelete();
        else changeStatus(UserStatus.DISABLED);
    }

    private static void assertConflict(ApiException failure, String code) {
        assertThat(failure).isNotNull();
        assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(failure.getCode()).isEqualTo(code);
    }

    private static String tokenHash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("stale user worker was not released");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("stale user worker was interrupted", exception);
        }
    }

    enum Invalidation { DISABLE, DELETE }

    enum Mutation { PASSWORD, DISABLE, REACTIVATE, DELETE }

    enum PasswordFailure { INVALID_ID, MISSING_USER, DISABLED, DELETED, TOO_LONG }
}
