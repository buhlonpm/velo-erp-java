package com.velo.asset;

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

@Entity
@Table(name = "asset_battery_details")
@DiscriminatorValue("battery")
@PrimaryKeyJoinColumn(name = "asset_id")
@Getter
@Setter
@NoArgsConstructor
public class BatteryAsset extends Asset {

    @Column
    private Integer voltage;

    /** Заводской номер/VIN батареи (необязательно). */
    @Column(name = "vin")
    private String vin;

    @Column(name = "capacity_ah")
    private Integer capacityAh;

    /** На каком велосипеде смонтирована (NULL — на складе). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bike_id")
    private BikeAsset bike;

    /** Куплена в комплекте с этим велосипедом (NULL — куплена отдельно). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bundled_bike_id")
    private BikeAsset bundledBike;

    /** Пробег — только ручной ввод (журнал пробега); пустой журнал → null. */
    @Column(name = "mileage_km")
    private Integer mileageKm = 0;

    /** Количество циклов перезарядки — ручной ввод, опционально. */
    @Column(name = "charge_cycles")
    private Integer chargeCycles;

    @Override
    public AssetType getType() {
        return AssetType.BATTERY;
    }
}
