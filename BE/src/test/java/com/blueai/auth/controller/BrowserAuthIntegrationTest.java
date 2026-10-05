package com.blueai.auth.controller;

import com.blueai.auth.repository.RefreshTokenRepository;
import com.blueai.auth.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrowserAuthIntegrationTest {
    private static final String LOGIN = """
            {"email":"browser@example.test","password":"StrongPassword123!"}
            """;
    @Autowired MockMvc mvc;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired UserRepository users;

    @BeforeEach
    void account() throws Exception {
        refreshTokens.deleteAll();
        users.deleteAll();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"browser@example.test","password":"StrongPassword123!","fullName":"QA Browser"}
                """)).andExpect(status().isCreated());
    }

    @Test
    void cookieIsHttpOnlyHostOnlyScopedAndRotationLogoutWork() throws Exception {
        MockHttpServletResponse login = mvc.perform(browser("login").content(LOGIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        Cookie original = login.getCookie(BrowserAuthController.COOKIE_NAME);
        assertThat(original).isNotNull();
        assertThat(original.isHttpOnly()).isTrue();
        assertThat(original.getPath()).isEqualTo(BrowserAuthController.COOKIE_PATH);
        assertThat(original.getDomain()).isNull();
        assertThat(login.getHeader("Set-Cookie")).contains("SameSite=Strict");
        assertThat(original.getMaxAge()).isEqualTo(86400);
        // A refresh cookie alone must never authenticate business endpoints.
        mvc.perform(get("/api/v1/auth/me").cookie(original)).andExpect(status().isUnauthorized());
        MockHttpServletResponse rotated = mvc.perform(browser("refresh").cookie(original))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn().getResponse();
        Cookie latest = rotated.getCookie(BrowserAuthController.COOKIE_NAME);
        assertThat(latest.getValue()).isNotEqualTo(original.getValue());
        mvc.perform(browser("logout").cookie(latest)).andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(BrowserAuthController.COOKIE_NAME, 0));
        mvc.perform(browser("refresh").cookie(latest)).andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge(BrowserAuthController.COOKIE_NAME, 0));
    }

    @Test
    void cookieReplayRevokesRotatedFamily() throws Exception {
        Cookie original = mvc.perform(browser("login").content(LOGIN)).andReturn().getResponse()
                .getCookie(BrowserAuthController.COOKIE_NAME);
        Cookie rotated = mvc.perform(browser("refresh").cookie(original)).andExpect(status().isOk())
                .andReturn().getResponse().getCookie(BrowserAuthController.COOKIE_NAME);
        mvc.perform(browser("refresh").cookie(original)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSED"));
        mvc.perform(browser("refresh").cookie(rotated)).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfBoundaryRejectsMissingOrUntrustedOriginsAndSimpleRequests() throws Exception {
        long before = refreshTokens.count();
        for (String action : new String[]{"login", "refresh", "logout"}) {
            String path = "/api/v1/auth/browser/" + action;
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).header("Origin", "http://localhost:3000")
                            .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                    .andExpect(status().isForbidden());
            mvc.perform(post(path).header("Origin", "https://attacker.example.test")
                            .header("X-BlueAI-Browser", "1").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                    .andExpect(status().isForbidden());
        }
        assertThat(refreshTokens.count()).isEqualTo(before);
    }

    @Test
    void missingOrAmbiguousCookiesFailClosedAndUnknownBrowserRouteIsPrivate() throws Exception {
        mvc.perform(browser("refresh")).andExpect(status().isUnauthorized());
        mvc.perform(browser("refresh").cookie(new Cookie("blueai_refresh", "one"), new Cookie("blueai_refresh", "two")))
                .andExpect(status().isUnauthorized());
        mvc.perform(browser("logout")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/browser/unknown")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder browser(String action) {
        return post("/api/v1/auth/browser/" + action).contentType(MediaType.APPLICATION_JSON)
                .header("Origin", "http://localhost:3000").header("X-BlueAI-Browser", "1");
    }
}
