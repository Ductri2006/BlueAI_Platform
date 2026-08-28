package com.blueai.auth.controller;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDatabase() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void registerAuthenticateRotateAndRevokeRefreshTokenFamily() throws Exception {
        JsonNode registered = postJson("/api/v1/auth/register", """
                {
                  "email": "Test.User@example.com",
                  "password": "StrongPassword123!",
                  "fullName": "Test User"
                }
                """, 201);

        String accessToken = registered.get("accessToken").asText();
        String firstRefreshToken = registered.get("refreshToken").asText();
        assertThat(registered.get("user").get("email").asText()).isEqualTo("test.user@example.com");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test.user@example.com"))
                .andExpect(jsonPath("$.role").value("USER"));

        JsonNode refreshed = postJson("/api/v1/auth/refresh", """
                {"refreshToken":"%s"}
                """.formatted(firstRefreshToken), 200);
        String rotatedRefreshToken = refreshed.get("refreshToken").asText();
        assertThat(rotatedRefreshToken).isNotEqualTo(firstRefreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(firstRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(rotatedRefreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void loginRejectsWrongPasswordAndLogoutRevokesSession() throws Exception {
        postJson("/api/v1/auth/register", """
                {
                  "email": "owner@example.com",
                  "password": "StrongPassword123!",
                  "fullName": "Owner"
                }
                """, 201);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "owner@example.com",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        JsonNode login = postJson("/api/v1/auth/login", """
                {
                  "email": "owner@example.com",
                  "password": "StrongPassword123!"
                }
                """, 200);
        String refreshToken = login.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void protectedEndpointRequiresAccessToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private JsonNode postJson(String path, String content, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }
}
