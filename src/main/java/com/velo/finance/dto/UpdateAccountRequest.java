package com.velo.finance.dto;

import com.velo.finance.AccountType;
import jakarta.validation.constraints.Size;

/** Все поля опциональны — меняется только переданное. */
public record UpdateAccountRequest(
        @Size(max = 255) String name,
        AccountType type
) {
}
