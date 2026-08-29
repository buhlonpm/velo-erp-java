package com.velo.asset;

import com.velo.bikemodel.BikeModel;
import com.velo.gps.GpsTracker;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "asset_bike_details")
@DiscriminatorValue("bike")
@PrimaryKeyJoinColumn(name = "asset_id")
@Getter
@Setter
@NoArgsConstructor
public class BikeAsset extends Asset {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    private BikeModel model;

    /** VIN рамы (необязательно); инвентарный номер — в базовом Asset. */
    @Column(name = "vin")
    private String vin;

    /** Текущий пробег — кэш последней записи из bike_mileage_logs. */
    @Column(name = "mileage_km", nullable = false)
    private int mileageKm = 0;

    /** Опциональный GPS-трекер из справочника. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gps_tracker_id")
    private GpsTracker gpsTracker;

    @Override
    public AssetType getType() {
        return AssetType.BIKE;
    }
}
