package com.velo.asset.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Все поля опциональны — меняется только переданное.
 * Статус здесь не меняется: он системный (аренды, приёмка на ТО).
 * Пробег — только через журнал (POST /assets/{id}/mileage),
 * циклы перезарядки — через журнал (POST /assets/{id}/charge-cycles).
 * Трекер — через POST/DELETE /assets/{id}/tracker.
 */
public record UpdateAssetRequest(
        @Size(max = 50) String inventoryNumber,
        @Size(max = 255) String name,
        @Size(max = 2000) String description,
        Instant purchasedAt,
        @Min(0) Integer purchasePrice,
        // bike
        UUID modelId,
        /** VIN рамы (bike) или заводской номер (battery). */
        @Size(max = 50) String vin,
        // battery
        @Min(0) Integer voltage,
        @Min(0) Integer capacityAh,
        // charger
        @Min(0) Integer powerW
) {
}
