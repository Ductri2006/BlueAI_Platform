package com.blueai.auth.controller;

import com.blueai.auth.config.CorsProperties;
import com.blueai.auth.config.JwtProperties;
import com.blueai.auth.dto.AuthResponse;
import com.blueai.auth.dto.LoginRequest;
import com.blueai.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrowserAuthCookieTest {
    @Test
    void productionCookieAlwaysRequiresHttpsAndNeverReturnsRefreshToken() {
        AuthService service = mock(AuthService.class);
        when(service.login(any())).thenReturn(new AuthResponse("qa-access", "qa-refresh", "Bearer", 900, null));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        BrowserAuthController controller = new BrowserAuthController(service, new CorsProperties(), new JwtProperties(), environment);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "http://localhost:3000");
        request.addHeader("X-BlueAI-Browser", "1");
        var result = controller.login(new LoginRequest("qa@example.test", "SyntheticPassword!"), request);
        assertThat(result.getHeaders().getFirst("Set-Cookie"))
                .contains("Secure", "HttpOnly", "SameSite=Strict", "Path=/api/v1/auth/browser")
                .doesNotContain("Domain=");
        assertThat(result.getBody().accessToken()).isEqualTo("qa-access");
        assertThat(result.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }
}
