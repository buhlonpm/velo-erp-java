package com.velo.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssetChargeCycleLogRepository extends JpaRepository<AssetChargeCycleLog, UUID> {

    /** Свежие первыми; при равном recorded_at — по времени вставки (created_at), чтобы порядок был детерминирован. */
    List<AssetChargeCycleLog> findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(UUID assetId);
}
