package com.velo.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AssetEventRepository extends JpaRepository<AssetEvent, UUID> {

    List<AssetEvent> findAllByAssetIdOrderByDateDesc(UUID assetId);

    java.util.Optional<AssetEvent> findFirstByAsset_IdAndType(UUID assetId, AssetEventType type);

    /** Снять ссылку на удаляемую операцию — событие остаётся в ленте. */
    @Modifying
    @Query("UPDATE AssetEvent e SET e.transaction = null WHERE e.transaction.id = :transactionId")
    void clearTransactionReference(@Param("transactionId") UUID transactionId);
}
