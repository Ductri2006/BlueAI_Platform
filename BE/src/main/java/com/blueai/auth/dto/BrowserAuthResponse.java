package com.blueai.auth.dto;

/** Browser clients never receive the refresh credential in JavaScript. */
public record BrowserAuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    public static BrowserAuthResponse from(AuthResponse response) {
        return new BrowserAuthResponse(response.accessToken(), response.tokenType(), response.expiresIn(), response.user());
    }
}
