package com.velo.user.dto;

import com.velo.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateUserRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 255) String fullName,
        @Size(max = 32) String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull Role role,
        Set<@Size(max = 64) String> permissions
) {
}
