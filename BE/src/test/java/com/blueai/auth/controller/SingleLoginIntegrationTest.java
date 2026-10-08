package com.blueai.auth.controller;

import com.blueai.auth.dto.AuthResponse;
import com.blueai.auth.dto.LoginRequest;
import com.blueai.auth.dto.RegisterRequest;
import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import com.blueai.auth.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:single-login-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SingleLoginIntegrationTest {
    private static final LoginRequest LOGIN = new LoginRequest("single@example.test", "StrongPassword123!");
    @Autowired AuthService auth;
    @Autowired JwtDecoder decoder;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired RefreshTokenRepository tokens;
    @Autowired UserRepository users;
    @Autowired com.blueai.auth.repository.PasswordResetTokenRepository resets;
    @Autowired com.blueai.auth.repository.AuthAuditLogRepository audits;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        resets.deleteAll(); tokens.deleteAll(); audits.deleteAll(); users.deleteAll();
        auth.register(new RegisterRequest(LOGIN.email(), LOGIN.password(), "Single session"));
    }

    @Test
    void newerLoginImmediatelyRejectsOldAccessAndRefreshEvenAfterRotation() throws Exception {
        AuthResponse first = auth.login(LOGIN);
        AuthResponse rotated = auth.refresh(first.refreshToken());
        AuthResponse second = auth.login(LOGIN);
        assertThatThrownBy(() -> decoder.decode(first.accessToken())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(rotated.accessToken())).isInstanceOf(JwtException.class);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + first.accessToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("refreshToken", rotated.refreshToken()))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REPLACED"));
        assertThat(decoder.decode(second.accessToken()).getSubject()).isEqualTo(second.user().id());
        assertThat(auth.refresh(second.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void failedLoginCannotKickOutActiveDeviceAndStaleLogoutCannotKickOutNewDevice() {
        AuthResponse first = auth.login(LOGIN);
        assertThatThrownBy(() -> auth.login(new LoginRequest(LOGIN.email(), "incorrect"))).hasMessageContaining("invalid");
        assertThat(decoder.decode(first.accessToken())).isNotNull();
        AuthResponse second = auth.login(LOGIN);
        auth.logout(first.refreshToken());
        assertThat(decoder.decode(second.accessToken())).isNotNull();
        assertThat(auth.refresh(second.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void currentLogoutRevokesAccessWithoutWaitingForJwtExpiry() {
        AuthResponse current = auth.login(LOGIN);
        auth.logout(current.refreshToken());
        assertThatThrownBy(() -> decoder.decode(current.accessToken())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> auth.refresh(current.refreshToken())).isInstanceOf(com.blueai.auth.exception.ApiException.class);
    }

    @Test
    void concurrentSuccessfulLoginsLeaveExactlyOneUsableFamily() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return auth.login(LOGIN); });
            var second = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return auth.login(LOGIN); });
            var responses = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            int validAccess = 0;
            int validRefresh = 0;
            for (var response : responses) {
                try { decoder.decode(response.accessToken()); validAccess++; } catch (JwtException expected) { }
                try { auth.refresh(response.refreshToken()); validRefresh++; } catch (com.blueai.auth.exception.ApiException expected) { }
            }
            assertThat(validAccess).isEqualTo(1);
            assertThat(validRefresh).isEqualTo(1);
        }
    }

    @Test
    void legacyAccessTokensWithoutSessionIdCannotReviveAfterLogout() {
        AuthResponse current = auth.login(LOGIN);
        var legacyClaims = new java.util.HashMap<String, Object>(decoder.decode(current.accessToken()).getClaims());
        legacyClaims.remove("sessionId");
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .claims(values -> values.putAll(legacyClaims)).build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader
                .with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).type("JWT").build();
        String legacyToken = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
        var migratedUser = users.findById(current.user().id()).orElseThrow();
        migratedUser.replaceSession(null);
        users.saveAndFlush(migratedUser);
        assertThatThrownBy(() -> auth.refresh(current.refreshToken())).isInstanceOf(com.blueai.auth.exception.ApiException.class);
        auth.logout(current.refreshToken());
        assertThatThrownBy(() -> decoder.decode(legacyToken)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> auth.refresh(current.refreshToken())).isInstanceOf(com.blueai.auth.exception.ApiException.class);
    }

    @Test
    void browserLoginReplacesPreviousBrowserCookieAndAccessToken() throws Exception {
        var request = java.util.Map.of("email", LOGIN.email(), "password", LOGIN.password());
        var first = mvc.perform(post("/api/v1/auth/browser/login").header("Origin", "http://localhost:3000")
                        .header("X-BlueAI-Browser", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn().getResponse();
        var second = mvc.perform(post("/api/v1/auth/browser/login").header("Origin", "http://localhost:3000")
                        .header("X-BlueAI-Browser", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn().getResponse();
        mvc.perform(post("/api/v1/auth/browser/refresh").header("Origin", "http://localhost:3000")
                        .header("X-BlueAI-Browser", "1").contentType(MediaType.APPLICATION_JSON)
                        .cookie(first.getCookie(BrowserAuthController.COOKIE_NAME)))
                .andExpect(status().isUnauthorized()).andExpect(cookie().maxAge(BrowserAuthController.COOKIE_NAME, 0));
        assertThatThrownBy(() -> decoder.decode(mapper.readTree(first.getContentAsString()).get("accessToken").asText())).isInstanceOf(JwtException.class);
        assertThat(decoder.decode(mapper.readTree(second.getContentAsString()).get("accessToken").asText())).isNotNull();
    }
}
