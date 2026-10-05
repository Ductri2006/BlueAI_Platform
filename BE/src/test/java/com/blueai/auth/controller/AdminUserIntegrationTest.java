package com.blueai.auth.controller;

import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.auth.public-registration-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void publicRegistrationIsUnavailable() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"self@example.com","password":"StrongPassword123!","fullName":"Self Register"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyAdminCanProvisionAndDisableAccounts() throws Exception {
        createUser("admin@example.com", "System Admin", UserRole.ADMIN);
        createUser("existing@example.com", "Existing User", UserRole.USER);
        String adminToken = login("admin@example.com");
        String userToken = login("existing@example.com");

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        String createdResponse = mockMvc.perform(post("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"new.user@example.com",
                                  "password":"TemporaryPassword123!",
                                  "fullName":"New User",
                                  "role":"USER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new.user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(createdResponse);
        mockMvc.perform(patch("/api/v1/admin/users/{userId}/status", created.get("id").asText())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"new.user@example.com","password":"TemporaryPassword123!"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DISABLED"));
    }

    @Test
    void adminUserSearchPaginatesFiltersAndTreatsLikeCharactersLiterally() throws Exception {
        createUser("admin-search@example.com", "System Admin", UserRole.ADMIN);
        createUser("100% alpha@example.com", "100% alpha", UserRole.USER);
        createUser("100% beta@example.com", "100% beta", UserRole.USER);
        createUser("1000 alpha@example.com", "1000 alpha", UserRole.USER);
        createUser("100% disabled@example.com", "100% disabled", UserRole.USER);
        User disabled = userRepository.findByEmail("100% disabled@example.com").orElseThrow();
        disabled.changeStatus(UserStatus.DISABLED);
        userRepository.saveAndFlush(disabled);
        User deleted = new User("100% deleted@example.com", passwordEncoder.encode("StrongPassword123!"), "100% deleted");
        deleted.softDelete(Instant.now());
        userRepository.saveAndFlush(deleted);

        // Equal timestamps prove the id tie-breaker keeps records on distinct pages deterministically.
        Timestamp sameCreatedAt = Timestamp.from(Instant.parse("2025-01-01T00:00:00Z"));
        jdbcTemplate.update("update app_users set created_at = ? where email in (?, ?)",
                sameCreatedAt, "100% alpha@example.com", "100% beta@example.com");
        String expectedFirst = userRepository.findByEmail("100% alpha@example.com").orElseThrow().getId()
                .compareTo(userRepository.findByEmail("100% beta@example.com").orElseThrow().getId()) < 0
                ? "100% alpha@example.com" : "100% beta@example.com";
        String expectedSecond = expectedFirst.equals("100% alpha@example.com")
                ? "100% beta@example.com" : "100% alpha@example.com";
        String adminToken = login("admin-search@example.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "100%")
                        .param("status", "ACTIVE")
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value(expectedFirst))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "100%")
                        .param("status", "ACTIVE")
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].email").value(expectedSecond))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void adminUserSearchCanIncludeDeletedAndRejectsInvalidPageBounds() throws Exception {
        createUser("admin-retention@example.com", "System Admin", UserRole.ADMIN);
        User deleted = new User("gone@example.com", passwordEncoder.encode("StrongPassword123!"), "Gone");
        deleted.softDelete(Instant.now());
        userRepository.saveAndFlush(deleted);
        String adminToken = login("admin-retention@example.com");

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "gone")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "gone")
                        .param("includeDeleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("DISABLED"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "gone")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_REQUEST"));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("search", "gone")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE_REQUEST"));
    }

    @Test
    void adminCannotReactivateOrChangeStatusOfDeletedUserTombstone() throws Exception {
        createUser("admin-tombstone@example.com", "System Admin", UserRole.ADMIN);
        User deleted = new User("tombstone@example.com", passwordEncoder.encode("StrongPassword123!"), "Tombstone");
        Instant deletedAt = Instant.parse("2025-02-03T04:05:06Z");
        deleted.softDelete(deletedAt);
        userRepository.saveAndFlush(deleted);
        String adminToken = login("admin-tombstone@example.com");

        mockMvc.perform(patch("/api/v1/admin/users/{userId}/status", deleted.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_DELETED"));

        User persisted = userRepository.findById(deleted.getId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(persisted.getDeletedAt()).isEqualTo(deletedAt);
    }

    private void createUser(String email, String fullName, UserRole role) {
        userRepository.saveAndFlush(new User(email, passwordEncoder.encode("StrongPassword123!"), fullName, role));
    }

    private String login(String email) throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"StrongPassword123!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
