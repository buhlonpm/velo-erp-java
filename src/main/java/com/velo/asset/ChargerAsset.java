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
@Table(name = "asset_charger_details")
@DiscriminatorValue("charger")
@PrimaryKeyJoinColumn(name = "asset_id")
@Getter
@Setter
@NoArgsConstructor
public class ChargerAsset extends Asset {

    @Column(name = "power_w")
    private Integer powerW;

    /** На каком велосипеде смонтирован (NULL — на складе). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bike_id")
    private BikeAsset bike;

    /** Куплен в комплекте с этим велосипедом (NULL — куплен отдельно). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bundled_bike_id")
    private BikeAsset bundledBike;

    @Override
    public AssetType getType() {
        return AssetType.CHARGER;
    }
}
