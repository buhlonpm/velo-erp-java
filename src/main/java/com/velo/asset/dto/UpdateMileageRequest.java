package com.velo.asset.dto;

import jakarta.validation.constraints.Min;

import java.time.Instant;

/** Правка записи журнала пробега: оба поля необязательны, null — оставить как было. */
public record UpdateMileageRequest(
        @Min(0) Integer mileageKm,
        Instant recordedAt
) {
}
