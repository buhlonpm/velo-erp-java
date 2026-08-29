package com.velo.asset.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RecordMileageRequest(
        @NotNull @Min(0) Integer mileageKm,
        /** Не передана — текущий момент. */
        Instant recordedAt
) {
}
