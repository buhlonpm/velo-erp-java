package com.velo.gps.dto;

import com.velo.gps.GpsTracker;

import java.time.Instant;
import java.util.UUID;

public record GpsTrackerResponse(
        UUID id,
        String model,
        String imei,
        UUID simCardId,
        String simPhoneNumber,
        String simOperator,
        Instant purchasedAt,
        Integer purchasePrice,
        String status,
        String writeOffReason,
        String writeOffComment,
        UUID installedBikeId,
        String installedBikeName,
        UUID writtenOffFromBikeId
) {
    public static GpsTrackerResponse from(GpsTracker tracker, UUID installedBikeId, String installedBikeName) {
        return new GpsTrackerResponse(
                tracker.getId(),
                tracker.getModel(),
                tracker.getImei(),
                tracker.getSimCard() != null ? tracker.getSimCard().getId() : null,
                tracker.getSimCard() != null ? tracker.getSimCard().getPhoneNumber() : null,
                tracker.getSimCard() != null ? tracker.getSimCard().getOperator() : null,
                tracker.getPurchasedAt(),
                tracker.getPurchasePrice(),
                switch (tracker.getStatus()) {
                    case ACTIVE -> "active";
                    case WRITTEN_OFF -> "written_off";
                    case SOLD -> "sold";
                },
                tracker.getWriteOffReason() != null ? tracker.getWriteOffReason().getValue() : null,
                tracker.getWriteOffComment(),
                installedBikeId,
                installedBikeName,
                tracker.getWrittenOffFromBike() != null ? tracker.getWrittenOffFromBike().getId() : null);
    }
}
