package com.blueai.auth.controller;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.AuthAuditLogRepository;
import com.blueai.auth.repository.PasswordResetTokenRepository;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.auth.service.AuthAuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordResetTokenRepository resetTokenRepository;
    @Autowired AuthAuditLogRepository auditLogRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AuthAuditLogService auditLogService;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        resetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void softDeleteHidesUserAndRevokesRefreshSessionWithoutHardDeletingRecords() throws Exception {
        User admin = save("admin@example.test", "Admin", UserRole.ADMIN);
        User target = save("target@example.test", "Target", UserRole.USER);
        JsonNode adminLogin = login("admin@example.test", "StrongPassword123!");
        JsonNode targetLogin = login("target@example.test", "StrongPassword123!");

        mockMvc.perform(delete("/api/v1/admin/users/{id}", target.getId())
                        .header("Authorization", "Bearer " + adminLogin.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + adminLogin.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='%s')]".formatted(target.getId())).doesNotExist());
        mockMvc.perform(get("/api/v1/admin/users?includeDeleted=true&page=0&size=20")
                        .header("Authorization", "Bearer " + adminLogin.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='%s')]".formatted(target.getId())).isNotEmpty());
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + targetLogin.get("accessToken").asText()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(targetLogin.get("refreshToken").asText())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));

        assertThat(userRepository.findById(target.getId())).isPresent();
        assertThat(userRepository.findById(target.getId()).orElseThrow().isDeleted()).isTrue();
        mockMvc.perform(get("/api/v1/admin/audit-logs?action=ADMIN_USER_DELETED&page=0&size=10")
                        .header("Authorization", "Bearer " + adminLogin.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("ADMIN_USER_DELETED"));

        mockMvc.perform(post("/api/v1/admin/auth-records/purge")
                        .header("Authorization", "Bearer " + adminLogin.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionDays\":30}"))
                .andExpect(status().isOk());
        assertThat(userRepository.findById(target.getId())).isPresent();
        assertThat(userRepository.findById(target.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void authenticatedUsersCanChangeTheirOwnPasswordAndMustLoginAgain() throws Exception {
        save("owner@example.test", "Owner", UserRole.USER);
        JsonNode login = login("owner@example.test", "StrongPassword123!");

        mockMvc.perform(post("/api/v1/auth/password/change")
                        .header("Authorization", "Bearer " + login.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"StrongPassword123!\",\"newPassword\":\"NewStrongPassword123!\"}"))
                .andExpect(status().isNoContent());
        login("owner@example.test", "NewStrongPassword123!");
    }

    @Test
    void auditAndRetentionOperationsUseAdminRoutesAndRemainAdminOnly() throws Exception {
        save("operations-admin@example.test", "Operations Admin", UserRole.ADMIN);
        save("operations-user@example.test", "Operations User", UserRole.USER);
        JsonNode adminLogin = login("operations-admin@example.test", "StrongPassword123!");
        JsonNode userLogin = login("operations-user@example.test", "StrongPassword123!");
        String adminToken = adminLogin.get("accessToken").asText();
        String userToken = userLogin.get("accessToken").asText();

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mockMvc.perform(get("/api/v1/admin/users/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(post("/api/v1/admin/auth-records/purge")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionDays\":30}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/auth-records/purge")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"retentionDays\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetTokensPurged").isNumber())
                .andExpect(jsonPath("$.refreshTokensPurged").isNumber())
                .andExpect(jsonPath("$.auditLogsPurged").isNumber());
    }

    @Test
    void deferredAuditUsesIndependentTransactionAndIsSkippedWhenCallerRollsBack() {
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        outerTransaction.executeWithoutResult(status -> auditLogService.recordAfterCommit(
                "AUDIT_AFTER_COMMIT_TEST", "SUCCESS", "actor-test", "target-test"
        ));

        assertThat(auditLogRepository.findAll())
                .anySatisfy(log -> {
                    assertThat(log.getAction()).isEqualTo("AUDIT_AFTER_COMMIT_TEST");
                    assertThat(log.getActorUserId()).isEqualTo("actor-test");
                });

        outerTransaction.executeWithoutResult(status -> {
            auditLogService.recordAfterCommit("AUDIT_ROLLBACK_TEST", "SUCCESS", "actor-test", "target-test");
            status.setRollbackOnly();
        });
        assertThat(auditLogRepository.findAll())
                .noneMatch(log -> "AUDIT_ROLLBACK_TEST".equals(log.getAction()));
    }

    @Test
    void prometheusIsPublicWhileMetricsRemainAdminOnly() throws Exception {
        save("metrics-user@example.test", "Metrics User", UserRole.USER);
        save("metrics-admin@example.test", "Metrics Admin", UserRole.ADMIN);
        JsonNode userLogin = login("metrics-user@example.test", "StrongPassword123!");
        JsonNode adminLogin = login("metrics-admin@example.test", "StrongPassword123!");

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("# HELP")));
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + userLogin.get("accessToken").asText()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + adminLogin.get("accessToken").asText()))
                .andExpect(status().isOk());
    }

    private User save(String email, String fullName, UserRole role) {
        return userRepository.saveAndFlush(new User(email, passwordEncoder.encode("StrongPassword123!"), fullName, role));
    }

    private JsonNode login(String email, String password) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }
}
