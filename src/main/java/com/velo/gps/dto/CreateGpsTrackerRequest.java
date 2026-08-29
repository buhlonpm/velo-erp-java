package com.velo.gps.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateGpsTrackerRequest(
        @NotBlank @Size(max = 100) String model,
        @Size(max = 32) String imei,
        UUID simCardId,
        Instant purchasedAt,
        @Min(0) Integer purchasePrice,
        /** Если задан вместе с purchasePrice — покупка списывается расходной операцией. */
        UUID purchaseAccountId
) {
}
