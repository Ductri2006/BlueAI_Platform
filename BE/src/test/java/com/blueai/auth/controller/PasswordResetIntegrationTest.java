package com.blueai.auth.controller;

import com.blueai.auth.model.AuthAuditLog;
import com.blueai.auth.model.PasswordResetToken;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.AuthAuditLogRepository;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordResetTokenRepository resetTokenRepository;
    @Autowired AuthAuditLogRepository auditLogRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanDatabase() {
        resetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void selfServiceResetAndChangeRoutesAreGoneForAuthenticatedUsers() throws Exception {
        User user = save("user@example.test", UserRole.USER);
        JsonNode session = login(user.getEmail(), "OldPassword123!");
        String authorization = "Bearer " + session.get("accessToken").asText();

        mockMvc.perform(post("/api/v1/auth/password-reset/request")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.test\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"anything\",\"newPassword\":\"ChangedPassword123!\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/auth/password/change")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"OldPassword123!\",\"newPassword\":\"ChangedPassword123!\"}"))
                .andExpect(status().isNotFound());

        login(user.getEmail(), "OldPassword123!");
        assertThat(resetTokenRepository.count()).isZero();
    }

    @Test
    void onlyAdminCanSetPasswordAndOldSessionsAreRevoked() throws Exception {
        User admin = save("admin@example.test", UserRole.ADMIN);
        User target = save("target@example.test", UserRole.USER);
        User otherUser = save("other@example.test", UserRole.USER);
        JsonNode adminSession = login(admin.getEmail(), "OldPassword123!");
        JsonNode targetSession = login(target.getEmail(), "OldPassword123!");
        JsonNode otherSession = login(otherUser.getEmail(), "OldPassword123!");
        resetTokenRepository.saveAndFlush(new PasswordResetToken(target, "a".repeat(64), Instant.now().plusSeconds(600)));
        String path = "/api/v1/admin/users/{userId}/password";
        String body = passwordBody("NewPassword123!");

        mockMvc.perform(post(path, target.getId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path, target.getId())
                        .header("Authorization", bearer(otherSession))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(passwordEncoder.matches("OldPassword123!", userRepository.findById(target.getId()).orElseThrow().getPasswordHash())).isTrue();

        String response = mockMvc.perform(post(path, target.getId())
                        .header("Authorization", bearer(adminSession))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).isEmpty();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(targetSession)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", targetSession.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", target.getEmail(), "password", "OldPassword123!"))))
                .andExpect(status().isUnauthorized());
        login(target.getEmail(), "NewPassword123!");
        assertThat(resetTokenRepository.count()).isZero();

        List<AuthAuditLog> passwordAudits = auditLogRepository.findAll().stream()
                .filter(log -> "ADMIN_PASSWORD_SET".equals(log.getAction())).toList();
        assertThat(passwordAudits).hasSize(1);
        AuthAuditLog audit = passwordAudits.getFirst();
        assertThat(audit.getOutcome()).isEqualTo("SUCCESS");
        assertThat(audit.getActorUserId()).isEqualTo(admin.getId());
        assertThat(audit.getTargetUserId()).isEqualTo(target.getId());
        assertThat(List.of(audit.getAction(), audit.getOutcome(), audit.getActorUserId(), audit.getTargetUserId()))
                .noneMatch(value -> value.contains("NewPassword123!") || value.contains(target.getEmail()));
    }

    @Test
    void rejectsBadPasswordsMissingAndInactiveTargetsWithoutChangingCredentials() throws Exception {
        User admin = save("admin@example.test", UserRole.ADMIN);
        User target = save("target@example.test", UserRole.USER);
        User disabled = save("disabled@example.test", UserRole.USER);
        disabled.changeStatus(UserStatus.DISABLED);
        userRepository.saveAndFlush(disabled);
        User deleted = save("deleted@example.test", UserRole.USER);
        deleted.softDelete(Instant.now());
        userRepository.saveAndFlush(deleted);
        JsonNode adminSession = login(admin.getEmail(), "OldPassword123!");
        String path = "/api/v1/admin/users/{userId}/password";
        String authorization = bearer(adminSession);

        for (String invalid : List.of("short", "x".repeat(73), "é".repeat(40))) {
            mockMvc.perform(post(path, target.getId())
                            .header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON).content(passwordBody(invalid)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post(path, target.getId())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(path, UUID.randomUUID().toString())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(passwordBody("NewPassword123!")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(path, "x".repeat(200))
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(passwordBody("NewPassword123!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_USER_ID"));
        for (User inactive : List.of(disabled, deleted)) {
            mockMvc.perform(post(path, inactive.getId())
                            .header("Authorization", authorization)
                            .contentType(MediaType.APPLICATION_JSON).content(passwordBody("NewPassword123!")))
                    .andExpect(status().isConflict());
        }
        login(target.getEmail(), "OldPassword123!");
        assertThat(auditLogRepository.findAll().stream()
                .filter(log -> "ADMIN_PASSWORD_SET".equals(log.getAction()) && "FAILURE".equals(log.getOutcome())))
                .hasSize(5);
        assertThat(auditLogRepository.findAll().stream()
                .filter(log -> "ADMIN_PASSWORD_SET".equals(log.getAction()) && log.getTargetUserId() == null))
                .hasSize(1);
    }

    private User save(String email, UserRole role) {
        return userRepository.saveAndFlush(new User(email, passwordEncoder.encode("OldPassword123!"), email, role));
    }

    private JsonNode login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private String bearer(JsonNode session) {
        return "Bearer " + session.get("accessToken").asText();
    }

    private String passwordBody(String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("newPassword", password));
    }
}
