package com.velo.gps.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateSimCardRequest(
        @NotBlank @Size(max = 32) String phoneNumber,
        @NotBlank @Size(max = 50) String operator,
        @Size(max = 255) String note,
        Instant purchasedAt,
        /** 0 — в комплекте с трекером. */
        @PositiveOrZero Integer purchasePrice,
        /** Обязателен, если purchasePrice > 0 — покупка списывается расходной операцией. */
        UUID purchaseAccountId
) {
}
