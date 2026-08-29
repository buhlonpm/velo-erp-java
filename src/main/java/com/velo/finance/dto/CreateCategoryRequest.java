package com.velo.finance.dto;

import com.velo.finance.CategoryKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull CategoryKind kind
) {
}
