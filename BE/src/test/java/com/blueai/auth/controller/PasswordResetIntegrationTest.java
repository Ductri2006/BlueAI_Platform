package com.blueai.auth.controller;

import com.blueai.auth.dto.PasswordResetConfirmRequest;
import com.blueai.auth.dto.PasswordResetRequest;
import com.blueai.auth.model.PasswordResetToken;
import com.blueai.auth.model.User;
import com.blueai.auth.repository.AuthAuditLogRepository;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.auth.service.PasswordResetDelivery;
import com.blueai.auth.service.PasswordResetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PasswordResetIntegrationTest.FakeDeliveryConfiguration.class)
@TestPropertySource(properties = {
        "app.auth.password-reset.enabled=true",
        "app.auth.password-reset.mail-from=no-reply@example.test",
        "app.auth.password-reset.frontend-url=https://app.example.test/reset-password",
        "app.auth.password-reset.request-cooldown=1ms"
})
class PasswordResetIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordResetTokenRepository resetTokenRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AuthAuditLogRepository auditLogRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired PasswordResetService passwordResetService;
    @Autowired CapturingDelivery delivery;

    @BeforeEach
    void cleanDatabase() {
        resetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        delivery.clear();
    }

    @Test
    void requestDoesNotEnumerateAndDeliveredTokenIsOneTime() throws Exception {
        User user = userRepository.saveAndFlush(new User(
                "reset@example.test", passwordEncoder.encode("OldPassword123!"), "Reset User"
        ));

        String existingResponse = request("reset@example.test");
        awaitDelivery();
        awaitDeliveryAudit(user.getId());
        String unknownResponse = request("unknown@example.test");
        assertThat(existingResponse).isEqualTo(unknownResponse);
        assertThat(existingResponse).doesNotContain("reset@example.test").doesNotContain("token");
        assertThat(delivery.rawToken).isNotBlank();
        assertThat(delivery.message).contains("token=" + delivery.rawToken);

        confirm(delivery.rawToken, "NewPassword123!").andExpect(status().isNoContent());
        confirm(delivery.rawToken, "AnotherPassword123!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));
        assertThat(passwordEncoder.matches("NewPassword123!", userRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        assertThat(auditLogRepository.findAll().stream().anyMatch(log -> "PASSWORD_RESET_DELIVERY".equals(log.getAction()))).isTrue();
    }

    @Test
    void expiredAndDisabledTokensCannotBeUsed() throws Exception {
        User user = userRepository.saveAndFlush(new User(
                "expiry@example.test", passwordEncoder.encode("OldPassword123!"), "Expiry User"
        ));
        request("expiry@example.test");
        awaitDelivery();
        String expiredToken = delivery.rawToken;
        PasswordResetToken token = resetTokenRepository.findAll().getFirst();
        Instant expiredAt = Instant.now().minusSeconds(1);
        jdbcTemplate.update("UPDATE password_reset_tokens SET expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(expiredAt), token.getId());
        assertThat(resetTokenRepository.findById(token.getId()).orElseThrow().isExpired(Instant.now())).isTrue();
        confirm(expiredToken, "NewPassword123!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));

        Thread.sleep(20);
        delivery.clear();
        request("expiry@example.test");
        awaitNewDeliveredToken(expiredToken);
        String disabledToken = delivery.rawToken;
        assertThat(disabledToken).isNotEqualTo(expiredToken);
        user.changeStatus(com.blueai.auth.model.UserStatus.DISABLED);
        userRepository.saveAndFlush(user);
        confirm(disabledToken, "NewPassword123!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));
    }

    @Test
    void twoConcurrentConfirmationsOnlyAllowOneWinner() throws Exception {
        userRepository.saveAndFlush(new User(
                "race@example.test", passwordEncoder.encode("OldPassword123!"), "Race User"
        ));
        request("race@example.test");
        awaitDelivery();
        String rawToken = delivery.rawToken;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Callable<Boolean>> tasks = List.of(
                () -> tryConfirm(rawToken, "NewPassword123!"),
                () -> tryConfirm(rawToken, "AnotherPassword123!")
        );
        long winners = executor.invokeAll(tasks).stream().filter(future -> {
            try { return future.get(); } catch (Exception exception) { return false; }
        }).count();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(winners).isEqualTo(1);
    }

    @Test
    void changingPasswordInvalidatesAnAlreadyDeliveredResetLink() throws Exception {
        User user = userRepository.saveAndFlush(new User(
                "change-reset@example.test", passwordEncoder.encode("OldPassword123!"), "Change Reset User"
        ));
        request(user.getEmail());
        awaitNewDeliveredToken(null);
        String staleResetToken = delivery.rawToken;
        JsonNode session = login(user.getEmail(), "OldPassword123!");

        mockMvc.perform(post("/api/v1/auth/password/change")
                        .header("Authorization", "Bearer " + session.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"OldPassword123!\",\"newPassword\":\"ChangedPassword123!\"}"))
                .andExpect(status().isNoContent());

        confirm(staleResetToken, "ResetWouldOverwrite123!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD_RESET_TOKEN"));
    }

    private boolean tryConfirm(String token, String password) {
        try {
            passwordResetService.confirm(new PasswordResetConfirmRequest(token, password));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String request(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest(email))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If the account exists, a password reset link has been sent."))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private void awaitDelivery() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (delivery.rawToken == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(delivery.rawToken).isNotBlank();
    }

    private void awaitDeliveryAudit(String userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            boolean recorded = auditLogRepository.findAll().stream()
                    .anyMatch(log -> "PASSWORD_RESET_DELIVERY".equals(log.getAction())
                            && userId.equals(log.getTargetUserId()));
            if (recorded) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(auditLogRepository.findAll().stream()
                .anyMatch(log -> "PASSWORD_RESET_DELIVERY".equals(log.getAction())
                        && userId.equals(log.getTargetUserId()))).isTrue();
    }

    private void awaitNewDeliveredToken(String previousToken) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            String current = delivery.rawToken;
            boolean committed = resetTokenRepository.findAll().stream()
                    .anyMatch(token -> "SENT".equals(token.getDeliveryStatus()));
            if (current != null && (previousToken == null || !current.equals(previousToken)) && committed) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(delivery.rawToken).isNotBlank();
        if (previousToken != null) {
            assertThat(delivery.rawToken).isNotEqualTo(previousToken);
        }
        assertThat(resetTokenRepository.findAll().stream()
                .anyMatch(token -> "SENT".equals(token.getDeliveryStatus()))).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PasswordResetConfirmRequest(token, password))));
    }

    @TestConfiguration
    static class FakeDeliveryConfiguration {
        @Bean
        @Primary
        CapturingDelivery capturingDelivery() {
            return new CapturingDelivery();
        }
    }

    static class CapturingDelivery implements PasswordResetDelivery {
        volatile String rawToken;
        volatile String message;

        @Override
        public void deliver(String recipientEmail, String rawToken) {
            this.rawToken = rawToken;
            this.message = "https://app.example.test/reset-password?token=" + rawToken;
        }

        void clear() { rawToken = null; message = null; }
    }
}
