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
        /** Покупка обязательна: дата, цена > 0 и счёт списания. */
        Instant purchasedAt,
        @Min(1) Integer purchasePrice,
        /** Обязателен — покупка списывается расходной операцией. */
        UUID purchaseAccountId
) {
}
