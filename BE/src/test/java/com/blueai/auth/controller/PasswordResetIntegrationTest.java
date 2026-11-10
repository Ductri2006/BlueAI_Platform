package com.blueai.auth.controller;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.repository.AuthAuditLogRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.auth.service.AuthService;
import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:password-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIntegrationTest {
    private static final String PASSWORD = "OldPassword123!";
    private static final String NEW_PASSWORD = "ChangedPassword456!";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired AuthAuditLogRepository audits;
    @Autowired AuthService authService;

    @Test
    void publicRecoveryStaysClosedAndAuthenticatedUserCanChangeOwnPassword() throws Exception {
        User user = user(UserRole.USER);
        JsonNode session = login(user, PASSWORD);
        mvc.perform(post("/api/v1/auth/password-reset/request").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/password-reset/confirm").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        change(session, Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD), 204);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(session))).andExpect(status().isUnauthorized());
        loginFails(user, PASSWORD);
        login(user, NEW_PASSWORD);
    }

    @Test
    void temporaryLoginIsSingleUseRestrictedAndCompletesRecovery() throws Exception {
        User admin = user(UserRole.ADMIN);
        User user = user(UserRole.USER);
        JsonNode adminSession = login(admin, PASSWORD);
        JsonNode oldSession = login(user, PASSWORD);
        JsonNode reset = reset(adminSession, user);
        String temporary = reset.get("temporaryPassword").asText();
        assertThat(temporary).hasSize(24).isNotEqualTo(PASSWORD);
        assertThat(Instant.parse(reset.get("expiresAt").asText())).isBetween(Instant.now().plusSeconds(850), Instant.now().plusSeconds(905));
        assertThat(encoder.matches(temporary, users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(oldSession))).andExpect(status().isUnauthorized());
        refresh(oldSession, 401);
        loginFails(user, PASSWORD);

        JsonNode restricted = login(user, temporary);
        assertThat(restricted.path("user").path("passwordChangeRequired").asBoolean()).isTrue();
        loginFails(user, temporary);
        for (String path : new String[]{"/api/v1/conversations", "/api/v1/documents", "/api/v1/admin/users", "/api/v1/auth/quota"}) {
            mvc.perform(get(path).header("Authorization", bearer(restricted))).andExpect(status().isForbidden());
        }
        JsonNode rotated = refresh(restricted, 200);
        mvc.perform(post("/api/v1/conversations").header("Authorization", bearer(rotated))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"must be denied\"}")).andExpect(status().isForbidden());
        change(rotated, Map.of("newPassword", temporary), 400);
        change(rotated, Map.of("newPassword", NEW_PASSWORD), 204);
        refresh(rotated, 401);
        loginFails(user, temporary);
        JsonNode normal = login(user, NEW_PASSWORD);
        assertThat(normal.path("user").path("passwordChangeRequired").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/conversations").header("Authorization", bearer(normal))).andExpect(status().isOk());
        assertThat(audits.findAll().stream().filter(a -> user.getId().equals(a.getTargetUserId())))
                .allSatisfy(a -> assertThat(a.toString()).doesNotContain(temporary, NEW_PASSWORD));
    }

    @Test
    void onlyAdminWithCurrentPasswordCanResetActiveUsers() throws Exception {
        User admin = user(UserRole.ADMIN);
        User user = user(UserRole.USER);
        JsonNode adminSession = login(admin, PASSWORD);
        JsonNode userSession = login(user, PASSWORD);
        String path = "/api/v1/admin/users/" + user.getId() + "/password";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body(Map.of("currentPassword", PASSWORD))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization", bearer(userSession)).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("currentPassword", PASSWORD)))).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", bearer(adminSession)).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("currentPassword", "wrong-password")))).andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Authorization", bearer(adminSession)).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("newPassword", NEW_PASSWORD)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/users/" + admin.getId() + "/password")
                .header("Authorization", bearer(adminSession)).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("currentPassword", PASSWORD)))).andExpect(status().isForbidden());
        loginFails(user, NEW_PASSWORD);
        login(user, PASSWORD);
    }

    @Test
    void secondResetInvalidatesPreviousTemporaryPasswordAndRestrictedSession() throws Exception {
        User admin = user(UserRole.ADMIN);
        User user = user(UserRole.USER);
        JsonNode adminSession = login(admin, PASSWORD);
        String first = reset(adminSession, user).get("temporaryPassword").asText();
        JsonNode restricted = login(user, first);
        String second = reset(adminSession, user).get("temporaryPassword").asText();
        assertThat(second).isNotEqualTo(first);
        loginFails(user, first);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(restricted))).andExpect(status().isUnauthorized());
        refresh(restricted, 401);
        change(login(user, second), Map.of("newPassword", NEW_PASSWORD), 204);
    }

    @Test
    void expiredCredentialCannotLoginRefreshOrChangePassword() throws Exception {
        User admin = user(UserRole.ADMIN);
        User user = user(UserRole.USER);
        String temporary = reset(login(admin, PASSWORD), user).get("temporaryPassword").asText();
        JsonNode restricted = login(user, temporary);
        User stored = users.findById(user.getId()).orElseThrow();
        stored.requirePasswordChange(Instant.now().minusSeconds(1));
        users.saveAndFlush(stored);
        loginFails(user, temporary);
        refresh(restricted, 401);
        change(restricted, Map.of("newPassword", NEW_PASSWORD), 401);
    }

    @Test
    void resetCannotBeBypassedByAnAlreadyValidatedOldSession() throws Exception {
        User admin = user(UserRole.ADMIN);
        User user = user(UserRole.USER);
        login(user, PASSWORD);
        User previous = users.findById(user.getId()).orElseThrow();
        reset(login(admin, PASSWORD), user);
        assertThatThrownBy(() -> authService.changePassword(user.getId(), previous.getActiveSessionId(),
                previous.getAuthVersion(), PASSWORD, NEW_PASSWORD)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus().value()).isEqualTo(401));
        assertThat(users.findById(user.getId()).orElseThrow().isPasswordChangeRequired()).isTrue();
    }

    @Test
    void usersCannotDeleteAccountsAndAdminCannotDeleteAnotherAdmin() throws Exception {
        User admin = user(UserRole.ADMIN);
        User otherAdmin = user(UserRole.ADMIN);
        User target = user(UserRole.USER);
        JsonNode adminSession = login(admin, PASSWORD);
        JsonNode targetSession = login(target, PASSWORD);
        mvc.perform(delete("/api/v1/admin/users/" + target.getId()).header("Authorization", bearer(targetSession)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/users/" + otherAdmin.getId()).header("Authorization", bearer(adminSession)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_DELETE_ONLY"));
        mvc.perform(delete("/api/v1/admin/users/" + target.getId()).header("Authorization", bearer(adminSession)))
                .andExpect(status().isOk());
        assertThat(users.findById(target.getId()).orElseThrow().isDeleted()).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(targetSession))).andExpect(status().isUnauthorized());
        refresh(targetSession, 401);
    }

    @Test
    void currentPasswordAndPasswordValidationAreEnforced() throws Exception {
        User user = user(UserRole.ADMIN);
        JsonNode session = login(user, PASSWORD);
        change(session, Map.of("newPassword", NEW_PASSWORD), 400);
        change(session, Map.of("currentPassword", "wrong-password", "newPassword", NEW_PASSWORD), 400);
        change(session, Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD), 400);
        change(session, Map.of("currentPassword", PASSWORD, "newPassword", "x"), 400);
        change(session, Map.of("currentPassword", PASSWORD, "newPassword", "é".repeat(40)), 400);
        login(user, PASSWORD);
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", encoder.encode(PASSWORD), "QA", role));
    }

    @Test
    void twoConcurrentLoginsCanConsumeTemporaryPasswordOnlyOnce() throws Exception {
        User admin = user(UserRole.ADMIN);
        User user = user(UserRole.USER);
        String temporary = reset(login(admin, PASSWORD), user).get("temporaryPassword").asText();
        String request = body(Map.of("email", user.getEmail(), "password", temporary));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var calls = java.util.stream.IntStream.range(0, 2).mapToObj(index ->
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try {
                            return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                                    .content(request)).andReturn().getResponse().getStatus();
                        } catch (Exception error) { throw new RuntimeException(error); }
                    }, pool)).toList();
            assertThat(calls.stream().map(java.util.concurrent.CompletableFuture::join).toList())
                    .containsExactlyInAnyOrder(200, 401);
        }
    }
    private JsonNode reset(JsonNode session, User user) throws Exception {
        String response = mvc.perform(post("/api/v1/admin/users/" + user.getId() + "/password")
                .header("Authorization", bearer(session)).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("currentPassword", PASSWORD))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
    private JsonNode login(User user, String password) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", user.getEmail(), "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private void loginFails(User user, String password) throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("email", user.getEmail(), "password", password)))).andExpect(status().isUnauthorized());
    }
    private JsonNode refresh(JsonNode session, int expectedStatus) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
    private void change(JsonNode session, Map<String, String> value, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/auth/password/change").header("Authorization", bearer(session))
                .contentType(MediaType.APPLICATION_JSON).content(body(value))).andExpect(status().is(expectedStatus));
    }
    private String bearer(JsonNode session) { return "Bearer " + session.get("accessToken").asText(); }
    private String body(Object value) throws Exception { return mapper.writeValueAsString(value); }
}
