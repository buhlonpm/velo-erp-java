package com.velo.bikemodel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** Все поля опциональны — меняется только переданное. */
public record UpdateBikeModelRequest(
        @Size(max = 100) String brand,
        @Size(max = 150) String model,
        @Size(max = 255) String specs,
        @Min(1) Integer maxMileageKm,
        @Min(0) @Max(100) Integer residualPercent
) {
}
