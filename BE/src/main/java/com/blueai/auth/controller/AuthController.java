package com.blueai.auth.controller;

import com.blueai.auth.dto.AuthResponse;
import com.blueai.auth.dto.ChangePasswordRequest;
import com.blueai.auth.dto.LoginRequest;
import com.blueai.auth.dto.LogoutRequest;
import com.blueai.auth.dto.PasswordResetConfirmRequest;
import com.blueai.auth.dto.PasswordResetRequest;
import com.blueai.auth.dto.PasswordResetResponse;
import com.blueai.auth.dto.RefreshRequest;
import com.blueai.auth.dto.UserResponse;
import com.blueai.auth.service.AuthService;
import com.blueai.auth.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    public AuthController(AuthService authService, PasswordResetService passwordResetService) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.getCurrentUser(jwt.getSubject());
    }

    @PostMapping("/password-reset/request")
    ResponseEntity<PasswordResetResponse> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        return ResponseEntity.accepted().body(passwordResetService.request(request.email()));
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password/change")
    ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        authService.changePassword(jwt.getSubject(), request);
        return ResponseEntity.noContent().build();
    }
}
