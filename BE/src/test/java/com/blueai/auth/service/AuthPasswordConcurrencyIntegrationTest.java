package com.blueai.auth.service;

import com.blueai.auth.dto.AuthResponse;
import com.blueai.auth.dto.LoginRequest;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.AuthAuditLogRepository;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(AuthPasswordConcurrencyIntegrationTest.EncoderConfiguration.class)
class AuthPasswordConcurrencyIntegrationTest {

    @Autowired AuthService authService;
    @Autowired AdminUserService adminUserService;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordResetTokenRepository resetTokenRepository;
    @Autowired AuthAuditLogRepository auditLogRepository;
    @Autowired PausingPasswordEncoder passwordEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        resetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void passwordResetCommittedDuringPasswordVerificationCannotLeaveAUsableSession() throws Exception {
        User admin = save("race-admin@example.test", UserRole.ADMIN);
        User target = save("race-user@example.test", UserRole.USER);
        passwordEncoder.pauseAfterVerificationOf(target.getPasswordHash());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<LoginAttempt> login = executor.submit(() -> {
                try {
                    return new LoginAttempt(authService.login(new LoginRequest(target.getEmail(), "OldPassword123!")), null);
                } catch (ApiException exception) {
                    return new LoginAttempt(null, exception);
                }
            });
            assertThat(passwordEncoder.verified.await(10, TimeUnit.SECONDS))
                    .as("login reached real password verification before admin reset")
                    .isTrue();

            // The reset uses a separate transaction and must commit before login resumes.
            adminUserService.setPassword(admin.getId(), target.getId(), "NewPassword123!");
            assertThat(userRepository.findById(target.getId()).orElseThrow().getAuthVersion()).isEqualTo(1);
            passwordEncoder.resume.countDown();
            LoginAttempt attempt = login.get(10, TimeUnit.SECONDS);

            if (attempt.session() != null) {
                assertThatThrownBy(() -> jwtDecoder.decode(attempt.session().accessToken()))
                        .isInstanceOf(JwtException.class);
                assertThatThrownBy(() -> authService.refresh(attempt.session().refreshToken()))
                        .as("a late session minted with the old password must not survive reset via refresh")
                        .isInstanceOf(ApiException.class);
            }
            assertThat(attempt.failure()).as("login must reject a credential snapshot invalidated by reset").isNotNull();
            assertThat(attempt.failure().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(attempt.failure().getCode()).isEqualTo("INVALID_CREDENTIALS");
            assertThat(refreshTokenRepository.count()).isZero();
            assertThat(auditLogRepository.findAll())
                    .anyMatch(log -> "LOGIN".equals(log.getAction()) && "FAILURE".equals(log.getOutcome())
                            && target.getId().equals(log.getTargetUserId()))
                    .noneMatch(log -> "LOGIN".equals(log.getAction()) && "SUCCESS".equals(log.getOutcome())
                            && target.getId().equals(log.getTargetUserId()));
            AuthResponse fresh = authService.login(new LoginRequest(target.getEmail(), "NewPassword123!"));
            assertThat(jwtDecoder.decode(fresh.accessToken()).getSubject()).isEqualTo(target.getId());
        } finally {
            passwordEncoder.resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void resetAfterCommittedLoginsInvalidatesEveryPreviouslyIssuedSession() {
        User admin = save("sessions-admin@example.test", UserRole.ADMIN);
        User target = save("sessions-user@example.test", UserRole.USER);
        AuthResponse first = authService.login(new LoginRequest(target.getEmail(), "OldPassword123!"));
        AuthResponse second = authService.login(new LoginRequest(target.getEmail(), "OldPassword123!"));
        AuthResponse rotated = authService.refresh(second.refreshToken());
        assertThat(refreshTokenRepository.count()).isEqualTo(3);

        adminUserService.setPassword(admin.getId(), target.getId(), "NewPassword123!");

        assertThat(refreshTokenRepository.findAll()).allMatch(token -> token.getRevokedAt() != null);
        for (AuthResponse session : List.of(first, second, rotated)) {
            assertThatThrownBy(() -> jwtDecoder.decode(session.accessToken())).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> authService.refresh(session.refreshToken())).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> authService.login(new LoginRequest(target.getEmail(), "OldPassword123!")))
                .isInstanceOf(ApiException.class);
        AuthResponse fresh = authService.login(new LoginRequest(target.getEmail(), "NewPassword123!"));
        assertThat(jwtDecoder.decode(fresh.accessToken()).getSubject()).isEqualTo(target.getId());
        assertThat(auditLogRepository.findAll().stream()
                .filter(log -> "ADMIN_PASSWORD_SET".equals(log.getAction()) && "SUCCESS".equals(log.getOutcome())))
                .singleElement().satisfies(log -> {
                    assertThat(log.getActorUserId()).isEqualTo(admin.getId());
                    assertThat(log.getTargetUserId()).isEqualTo(target.getId());
                });
    }

    @Test
    void resetOverlappingAnUncommittedRefreshInvalidatesTheRotatedSession() throws Exception {
        User admin = save("refresh-admin@example.test", UserRole.ADMIN);
        User target = save("refresh-user@example.test", UserRole.USER);
        AuthResponse current = authService.login(new LoginRequest(target.getEmail(), "OldPassword123!"));
        CountDownLatch refreshedBeforeCommit = new CountDownLatch(1);
        CountDownLatch commitRefresh = new CountDownLatch(1);
        passwordEncoder.observeEncodingOf("NewPassword123!");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuthResponse> refresh = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                AuthResponse next = authService.refresh(current.refreshToken());
                refreshedBeforeCommit.countDown();
                await(commitRefresh, "test did not release the refresh transaction");
                return next;
            }));
            assertThat(refreshedBeforeCommit.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> reset = executor.submit(() -> adminUserService.setPassword(admin.getId(), target.getId(), "NewPassword123!"));
            assertThat(passwordEncoder.encoded.await(10, TimeUnit.SECONDS))
                    .as("reset acquired the user row while refresh still holds its token row")
                    .isTrue();
            commitRefresh.countDown();
            AuthResponse rotated = refresh.get(10, TimeUnit.SECONDS);
            reset.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> jwtDecoder.decode(rotated.accessToken())).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> authService.refresh(rotated.refreshToken())).isInstanceOf(ApiException.class);
            assertThat(refreshTokenRepository.findAll()).allMatch(token -> token.getRevokedAt() != null);
        } finally {
            commitRefresh.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectedPasswordResetDoesNotInvalidateAnExistingSession() {
        User admin = save("validation-admin@example.test", UserRole.ADMIN);
        User target = save("validation-user@example.test", UserRole.USER);
        AuthResponse current = authService.login(new LoginRequest(target.getEmail(), "OldPassword123!"));

        assertThatThrownBy(() -> adminUserService.setPassword(admin.getId(), target.getId(), "é".repeat(40)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getCode()).isEqualTo("PASSWORD_TOO_LONG");
                });

        User unchanged = userRepository.findById(target.getId()).orElseThrow();
        assertThat(unchanged.getAuthVersion()).isZero();
        assertThat(unchanged.getPasswordHash()).isEqualTo(target.getPasswordHash());
        assertThat(jwtDecoder.decode(current.accessToken()).getSubject()).isEqualTo(target.getId());
        AuthResponse rotated = authService.refresh(current.refreshToken());
        assertThat(jwtDecoder.decode(rotated.accessToken()).getSubject()).isEqualTo(target.getId());
        assertThat(auditLogRepository.findAll())
                .anyMatch(log -> "ADMIN_PASSWORD_SET".equals(log.getAction()) && "FAILURE".equals(log.getOutcome())
                        && admin.getId().equals(log.getActorUserId()) && target.getId().equals(log.getTargetUserId()))
                .noneMatch(log -> "ADMIN_PASSWORD_SET".equals(log.getAction()) && "SUCCESS".equals(log.getOutcome()));
    }

    @Test
    void rolledBackPasswordResetKeepsExistingSessionsAndDoesNotAuditSuccess() {
        User admin = save("rollback-admin@example.test", UserRole.ADMIN);
        User target = save("rollback-user@example.test", UserRole.USER);
        AuthResponse current = authService.login(new LoginRequest(target.getEmail(), "OldPassword123!"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            adminUserService.setPassword(admin.getId(), target.getId(), "NewPassword123!");
            status.setRollbackOnly();
        });

        User unchanged = userRepository.findById(target.getId()).orElseThrow();
        assertThat(unchanged.getAuthVersion()).isZero();
        assertThat(unchanged.getPasswordHash()).isEqualTo(target.getPasswordHash());
        assertThat(refreshTokenRepository.findAll()).allMatch(token -> token.getRevokedAt() == null);
        assertThat(jwtDecoder.decode(current.accessToken()).getSubject()).isEqualTo(target.getId());
        assertThat(jwtDecoder.decode(authService.refresh(current.refreshToken()).accessToken()).getSubject())
                .isEqualTo(target.getId());
        assertThat(auditLogRepository.findAll()).noneMatch(log -> "ADMIN_PASSWORD_SET".equals(log.getAction()));
    }

    private static void await(CountDownLatch latch, String failure) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(failure);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, exception);
        }
    }

    private User save(String email, UserRole role) {
        return userRepository.saveAndFlush(new User(email, passwordEncoder.encode("OldPassword123!"), "Race Test", role));
    }

    private record LoginAttempt(AuthResponse session, ApiException failure) {
    }

    @TestConfiguration
    static class EncoderConfiguration {
        @Bean
        @Primary
        PausingPasswordEncoder pausingPasswordEncoder() {
            return new PausingPasswordEncoder();
        }
    }

    static class PausingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile String targetHash;
        private CountDownLatch verified = new CountDownLatch(1);
        private CountDownLatch resume = new CountDownLatch(1);
        private volatile String observedPassword;
        private CountDownLatch encoded = new CountDownLatch(1);

        void pauseAfterVerificationOf(String hash) {
            targetHash = hash;
            verified = new CountDownLatch(1);
            resume = new CountDownLatch(1);
            armed.set(true);
        }

        void observeEncodingOf(String rawPassword) {
            observedPassword = rawPassword;
            encoded = new CountDownLatch(1);
        }

        @Override
        public String encode(CharSequence rawPassword) {
            String hash = delegate.encode(rawPassword);
            if (rawPassword.toString().equals(observedPassword)) {
                encoded.countDown();
            }
            return hash;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            boolean matches = delegate.matches(rawPassword, encodedPassword);
            if (matches && encodedPassword.equals(targetHash) && armed.compareAndSet(true, false)) {
                verified.countDown();
                try {
                    if (!resume.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release password verification");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("password verification interrupted", exception);
                }
            }
            return matches;
        }
    }
}
