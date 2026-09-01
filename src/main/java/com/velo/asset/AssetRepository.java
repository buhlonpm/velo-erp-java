package com.velo.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AssetRepository extends JpaRepository<Asset, UUID> {

    boolean existsByInventoryNumber(String inventoryNumber);

    List<Asset> findAllByOrderByInventoryNumber();

    List<Asset> findAllByStatusOrderByInventoryNumber(AssetStatus status);

    @Query("SELECT a FROM #{#entityName} a WHERE TYPE(a) = :type ORDER BY a.inventoryNumber")
    List<Asset> findAllByType(@Param("type") Class<? extends Asset> type);

    @Query("SELECT a FROM #{#entityName} a WHERE TYPE(a) = :type AND a.status = :status ORDER BY a.inventoryNumber")
    List<Asset> findAllByTypeAndStatus(@Param("type") Class<? extends Asset> type,
                                       @Param("status") AssetStatus status);

    @Query("SELECT b FROM BikeAsset b WHERE b.gpsTracker.id = :trackerId")
    java.util.Optional<BikeAsset> findBikeByGpsTrackerId(@Param("trackerId") UUID trackerId);

    @Query("SELECT b FROM BatteryAsset b WHERE b.bike.id = :bikeId")
    java.util.List<BatteryAsset> findAllBatteriesByBikeId(@Param("bikeId") UUID bikeId);

    @Query("SELECT c FROM ChargerAsset c WHERE c.bike.id = :bikeId")
    java.util.List<ChargerAsset> findAllChargersByBikeId(@Param("bikeId") UUID bikeId);

    /** Комплектные АКБ/зарядник, купленные вместе с велосипедом (наследуют его дату покупки). */
    @Query("SELECT b FROM BatteryAsset b WHERE b.bundledBike.id = :bikeId")
    java.util.List<BatteryAsset> findAllBatteriesByBundledBikeId(@Param("bikeId") UUID bikeId);

    @Query("SELECT c FROM ChargerAsset c WHERE c.bundledBike.id = :bikeId")
    java.util.List<ChargerAsset> findAllChargersByBundledBikeId(@Param("bikeId") UUID bikeId);

}
