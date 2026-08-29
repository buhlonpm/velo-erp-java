package com.velo.asset.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RecordChargeCyclesRequest(
        @NotNull @Min(0) Integer cycles,
        /** Не передана — текущий момент. */
        Instant recordedAt
) {
}
