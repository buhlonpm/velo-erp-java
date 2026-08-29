package com.velo.asset.dto;

import com.velo.asset.Asset;
import com.velo.asset.AssetStatus;
import com.velo.asset.AssetType;
import com.velo.asset.BatteryAsset;
import com.velo.asset.BikeAsset;
import com.velo.asset.ChargerAsset;
import com.velo.bikemodel.dto.BikeModelResponse;

import java.time.Instant;
import java.util.UUID;

public record AssetResponse(
        UUID id,
        AssetType type,
        String inventoryNumber,
        String name,
        String description,
        AssetStatus status,
        String writeOffReason,
        Instant writtenOffAt,
        Instant purchasedAt,
        Integer purchasePrice,
        /** VIN рамы (bike) или заводской номер (battery), null у зарядников. */
        String vin,
        // bike
        UUID modelId,
        String modelName,
        Integer mileageKm,
        UUID gpsTrackerId,
        String gpsTrackerModel,
        String gpsSimNumber,
        String gpsOperator,
        // battery
        Integer voltage,
        Integer capacityAh,
        Integer chargeCycles,
        // battery/charger
        UUID bikeId,
        String bikeName,
        UUID bundledBikeId,
        String bundledBikeName,
        // charger
        Integer powerW,
        String connector
) {
    public static AssetResponse from(Asset asset) {
        String vin = null;
        UUID modelId = null;
        String modelName = null;
        Integer mileageKm = null;
        UUID gpsTrackerId = null;
        String gpsTrackerModel = null;
        String gpsSimNumber = null;
        String gpsOperator = null;
        Integer voltage = null;
        Integer capacityAh = null;
        Integer chargeCycles = null;
        UUID bikeId = null;
        String bikeName = null;
        UUID bundledBikeId = null;
        String bundledBikeName = null;
        Integer powerW = null;
        String connector = null;

        if (asset instanceof BikeAsset bike) {
            vin = bike.getVin();
            if (bike.getModel() != null) {
                modelId = bike.getModel().getId();
                modelName = BikeModelResponse.displayName(bike.getModel());
            }
            mileageKm = bike.getMileageKm();
            if (bike.getGpsTracker() != null) {
                gpsTrackerId = bike.getGpsTracker().getId();
                gpsTrackerModel = bike.getGpsTracker().getModel();
                if (bike.getGpsTracker().getSimCard() != null) {
                    gpsSimNumber = bike.getGpsTracker().getSimCard().getPhoneNumber();
                    gpsOperator = bike.getGpsTracker().getSimCard().getOperator();
                }
            }
        } else if (asset instanceof BatteryAsset battery) {
            vin = battery.getVin();
            voltage = battery.getVoltage();
            capacityAh = battery.getCapacityAh();
            chargeCycles = battery.getChargeCycles();
            mileageKm = battery.getMileageKm();
            if (battery.getBike() != null) {
                bikeId = battery.getBike().getId();
                bikeName = battery.getBike().getName() + " (" + battery.getBike().getInventoryNumber() + ")";
            }
            if (battery.getBundledBike() != null) {
                bundledBikeId = battery.getBundledBike().getId();
                bundledBikeName = battery.getBundledBike().getName()
                        + " (" + battery.getBundledBike().getInventoryNumber() + ")";
            }
        } else if (asset instanceof ChargerAsset charger) {
            powerW = charger.getPowerW();
            connector = charger.getConnector();
            if (charger.getBike() != null) {
                bikeId = charger.getBike().getId();
                bikeName = charger.getBike().getName() + " (" + charger.getBike().getInventoryNumber() + ")";
            }
            if (charger.getBundledBike() != null) {
                bundledBikeId = charger.getBundledBike().getId();
                bundledBikeName = charger.getBundledBike().getName()
                        + " (" + charger.getBundledBike().getInventoryNumber() + ")";
            }
        }

        return new AssetResponse(
                asset.getId(),
                asset.getType(),
                asset.getInventoryNumber(),
                asset.getName(),
                asset.getDescription(),
                asset.getStatus(),
                asset.getWriteOffReason() != null ? asset.getWriteOffReason().getValue() : null,
                asset.getWrittenOffAt(),
                asset.getPurchasedAt(),
                asset.getPurchasePrice(),
                vin,
                modelId, modelName, mileageKm,
                gpsTrackerId, gpsTrackerModel, gpsSimNumber, gpsOperator,
                voltage, capacityAh, chargeCycles, bikeId, bikeName, bundledBikeId, bundledBikeName,
                powerW, connector);
    }
}
