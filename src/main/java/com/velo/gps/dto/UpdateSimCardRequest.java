package com.velo.gps.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/** Все поля опциональны — меняется только переданное. */
public record UpdateSimCardRequest(
        @Size(max = 32) String phoneNumber,
        @Size(max = 50) String operator,
        @Size(max = 255) String note,
        /** Правка покупки — только у отдельно купленной симки; операция покупки синхронизируется. */
        Instant purchasedAt,
        /** <= 0 → 409 из сервиса (не @Positive, чтобы не было 400). */
        Integer purchasePrice
) {
}
