package com.velo.asset.dto;

import com.velo.asset.AssetChargeCycleLog;

import java.time.Instant;
import java.util.UUID;

public record ChargeCycleLogEntry(
        UUID id,
        int cycles,
        Instant recordedAt
) {
    public static ChargeCycleLogEntry from(AssetChargeCycleLog log) {
        return new ChargeCycleLogEntry(log.getId(), log.getCycles(), log.getRecordedAt());
    }
}
