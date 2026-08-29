package com.velo.bikemodel.dto;

import com.velo.bikemodel.BikeModel;
import com.velo.tariff.dto.TariffResponse;

import java.util.List;
import java.util.UUID;

public record BikeModelResponse(
        UUID id,
        String brand,
        String model,
        String specs,
        Integer maxMileageKm,
        Integer residualPercent,
        List<TariffResponse> tariffs
) {
    public static BikeModelResponse from(BikeModel model, List<TariffResponse> tariffs) {
        return new BikeModelResponse(
                model.getId(),
                model.getBrand(),
                model.getModel(),
                model.getSpecs(),
                model.getMaxMileageKm(),
                model.getResidualPercent(),
                tariffs);
    }

    /** «Wenbox U7 Pro 60V 45Ah» */
    public static String displayName(BikeModel model) {
        return (model.getBrand() + " " + model.getModel()
                + (model.getSpecs().isBlank() ? "" : " " + model.getSpecs())).trim();
    }
}
