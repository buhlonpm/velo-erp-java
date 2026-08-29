package com.velo.gps.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Все поля опциональны — меняется только переданное.
 * simCardId — заменить симку; clearSimCard=true — вынуть симку (без замены).
 */
public record UpdateGpsTrackerRequest(
        @Size(max = 100) String model,
        @Size(max = 32) String imei,
        UUID simCardId,
        Boolean clearSimCard
) {
}
