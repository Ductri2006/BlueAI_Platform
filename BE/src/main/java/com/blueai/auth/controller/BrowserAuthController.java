package com.blueai.auth.controller;

import com.blueai.auth.config.CorsProperties;
import com.blueai.auth.config.JwtProperties;
import com.blueai.auth.dto.AuthResponse;
import com.blueai.auth.dto.BrowserAuthResponse;
import com.blueai.auth.dto.LoginRequest;
import com.blueai.auth.exception.ApiException;
import com.blueai.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth/browser")
public class BrowserAuthController {
    static final String COOKIE_NAME = "blueai_refresh";
    static final String COOKIE_PATH = "/api/v1/auth/browser";
    private final AuthService authService;
    private final CorsProperties corsProperties;
    private final JwtProperties jwtProperties;
    private final boolean secureCookie;

    public BrowserAuthController(AuthService authService, CorsProperties corsProperties,
                                 JwtProperties jwtProperties, Environment environment) {
        this.authService = authService;
        this.corsProperties = corsProperties;
        this.jwtProperties = jwtProperties;
        this.secureCookie = environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    @PostMapping("/login")
    ResponseEntity<BrowserAuthResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        requireBrowserOrigin(request);
        return authenticated(authService.login(body));
    }

    @PostMapping("/refresh")
    ResponseEntity<BrowserAuthResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        requireBrowserOrigin(request);
        try {
            String credential = refreshCredential(request);
            if (credential == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "The browser session has expired.");
            }
            return authenticated(authService.refresh(credential));
        } catch (ApiException exception) {
            if (exception.getStatus() == HttpStatus.UNAUTHORIZED) {
                response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            }
            throw exception;
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        requireBrowserOrigin(request);
        String credential = refreshCredential(request);
        if (credential != null) authService.logout(credential);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString()).build();
    }

    private ResponseEntity<BrowserAuthResponse> authenticated(AuthResponse response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie(response.refreshToken(), jwtProperties.getRefreshTokenTtl()).toString())
                .body(BrowserAuthResponse.from(response));
    }

    private ResponseCookie cookie(String value, Duration lifetime) {
        return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path(COOKIE_PATH).maxAge(lifetime).build();
    }

    private void requireBrowserOrigin(HttpServletRequest request) {
        // Only these three routes authenticate with cookies. Requiring an exact
        // trusted Origin AND a non-simple header prevents cross-site form CSRF.
        // All business endpoints continue to authenticate exclusively by Bearer.
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !corsProperties.getAllowedOrigins().contains(origin)
                || !"1".equals(request.getHeader("X-BlueAI-Browser"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BROWSER_ORIGIN_REJECTED", "Browser origin verification failed.");
        }
    }

    private String refreshCredential(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        List<Cookie> matches = Arrays.stream(cookies).filter(cookie -> COOKIE_NAME.equals(cookie.getName())).toList();
        if (matches.isEmpty()) return null;
        if (matches.size() != 1 || matches.getFirst().getValue().isBlank() || matches.getFirst().getValue().length() > 256) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Invalid browser session cookie.");
        }
        return matches.getFirst().getValue();
    }
}
