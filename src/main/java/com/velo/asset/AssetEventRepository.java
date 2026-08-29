package com.velo.asset;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AssetEventRepository extends JpaRepository<AssetEvent, UUID> {

    List<AssetEvent> findAllByAssetIdOrderByDateDesc(UUID assetId);

    java.util.Optional<AssetEvent> findFirstByAsset_IdAndType(UUID assetId, AssetEventType type);
}
