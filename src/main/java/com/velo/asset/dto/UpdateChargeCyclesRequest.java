package com.velo.asset.dto;

import jakarta.validation.constraints.Min;

import java.time.Instant;

/** Правка записи журнала циклов перезарядки: оба поля необязательны, null — оставить как было. */
public record UpdateChargeCyclesRequest(
        @Min(0) Integer cycles,
        Instant recordedAt
) {
}
