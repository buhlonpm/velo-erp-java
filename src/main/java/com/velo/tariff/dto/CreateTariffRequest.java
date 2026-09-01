package com.velo.tariff.dto;

import com.velo.rental.RentalKind;
import com.velo.tariff.TariffUnit;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTariffRequest(
        @NotNull UUID modelId,
        @NotBlank @Size(max = 100) String name,
        @NotNull TariffUnit unit,
        @Positive int price,
        /** Вид договора: rent (по умолчанию) / rent_to_own (строго unit=week, один на модель). */
        RentalKind kind
) {
}
