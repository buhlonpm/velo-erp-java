package com.velo.user.dto;

import com.velo.user.Role;
import com.velo.user.User;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String phone,
        Role role,
        boolean active,
        Set<String> permissions,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole(),
                user.isActive(),
                Set.copyOf(user.getPermissions()),
                user.getCreatedAt());
    }
}
