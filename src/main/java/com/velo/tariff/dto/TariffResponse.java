package com.velo.tariff.dto;

import com.velo.rental.RentalKind;
import com.velo.tariff.Tariff;
import com.velo.tariff.TariffUnit;

import java.util.UUID;

public record TariffResponse(
        UUID id,
        String name,
        TariffUnit unit,
        int price,
        RentalKind kind
) {
    public static TariffResponse from(Tariff tariff) {
        return new TariffResponse(tariff.getId(), tariff.getName(), tariff.getUnit(), tariff.getPrice(),
                tariff.getKind());
    }
}
