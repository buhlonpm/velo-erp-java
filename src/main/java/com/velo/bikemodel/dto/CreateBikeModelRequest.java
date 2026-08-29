package com.velo.bikemodel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBikeModelRequest(
        @NotBlank @Size(max = 100) String brand,
        @NotBlank @Size(max = 150) String model,
        @Size(max = 255) String specs,
        @Min(1) Integer maxMileageKm,
        @Min(0) @Max(100) Integer residualPercent
) {
}
