package com.velo.auth.dto;

import com.velo.user.Role;
import com.velo.user.User;

import java.util.Set;
import java.util.UUID;

public record UserInfo(
        UUID id,
        String fullName,
        String email,
        Role role,
        Set<String> permissions
) {
    public static UserInfo from(User user) {
        return new UserInfo(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                Set.copyOf(user.getPermissions()));
    }
}
