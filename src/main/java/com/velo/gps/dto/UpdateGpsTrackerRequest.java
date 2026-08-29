package com.velo.gps.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Все поля опциональны — меняется только переданное.
 * simCardId — заменить симку; clearSimCard=true — вынуть симку (без замены).
 * purchasedAt/purchasePrice — правка покупки; системная операция покупки синхронизируется.
 */
public record UpdateGpsTrackerRequest(
        @Size(max = 100) String model,
        @Size(max = 32) String imei,
        UUID simCardId,
        Boolean clearSimCard,
        Instant purchasedAt,
        /** <= 0 → 409 из сервиса (не @Positive, чтобы не было 400). */
        Integer purchasePrice
) {
}
