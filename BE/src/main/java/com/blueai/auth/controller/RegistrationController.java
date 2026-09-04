package com.blueai.auth.controller;

import com.blueai.auth.dto.AuthResponse;
import com.blueai.auth.dto.RegisterRequest;
import com.blueai.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only compatibility endpoint. Public registration is disabled by default. */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "app.auth.public-registration-enabled", havingValue = "true")
class RegistrationController {

    private final AuthService authService;

    RegistrationController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }
}
