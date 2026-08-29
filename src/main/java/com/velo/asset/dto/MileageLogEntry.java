package com.velo.asset.dto;

import com.velo.asset.AssetMileageLog;

import java.time.Instant;
import java.util.UUID;

public record MileageLogEntry(
        UUID id,
        int mileageKm,
        Instant recordedAt
) {
    public static MileageLogEntry from(AssetMileageLog log) {
        return new MileageLogEntry(log.getId(), log.getMileageKm(), log.getRecordedAt());
    }
}
