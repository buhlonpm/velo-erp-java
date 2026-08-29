package com.velo.finance.dto;

import com.velo.finance.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull AccountType type
) {
}
