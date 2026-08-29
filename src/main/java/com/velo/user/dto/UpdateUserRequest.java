package com.velo.user.dto;

import com.velo.user.Role;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Все поля опциональны — меняется только переданное.
 * permissions, если передан, заменяет весь набор прав целиком.
 */
public record UpdateUserRequest(
        @Size(max = 255) String fullName,
        @Size(max = 32) String phone,
        Role role,
        Boolean active,
        @Size(min = 8, max = 72) String password,
        Set<@Size(max = 64) String> permissions
) {
}
