package com.velo.asset.dto;

import com.velo.asset.AssetType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Плоский запрос: общие поля + типо-специфичные (берутся по type).
 * purchaseAccountId + purchasePrice → покупка списывается расходной операцией со счёта.
 */
public record CreateAssetRequest(
        @NotNull AssetType type,
        @NotBlank @Size(max = 50) String inventoryNumber,
        @Size(max = 255) String name,
        @Size(max = 2000) String description,
        Instant purchasedAt,
        @Min(0) Integer purchasePrice,
        /** Если задан вместе с purchasePrice — создаётся расходная операция на покупку. */
        UUID purchaseAccountId,
        /** «В комплекте с велосипедом» (только АКБ/зарядник): цена обязана быть 0,
         *  актив сразу монтируется на этот велосипед. */
        UUID bundledBikeId,
        // bike
        UUID modelId,
        @Min(0) Integer mileageKm,
        UUID gpsTrackerId,
        /** VIN рамы (bike) или заводской номер (battery), необязательно. */
        @Size(max = 50) String vin,
        // battery
        @Min(0) Integer voltage,
        @Min(0) Integer capacityAh,
        // charger
        @Min(0) Integer powerW,
        @Size(max = 50) String connector
) {
}
