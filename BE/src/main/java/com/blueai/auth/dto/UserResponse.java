package com.blueai.auth.dto;

import com.blueai.auth.model.User;

import java.time.Instant;

public record UserResponse(
        String id,
        String email,
        String fullName,
        String role,
        String status,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getCreatedAt()
        );
    }
}
