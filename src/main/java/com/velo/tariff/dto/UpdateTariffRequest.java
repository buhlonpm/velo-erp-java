package com.velo.tariff.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Все поля опциональны — меняется только переданное. Влияет только на будущие аренды. */
public record UpdateTariffRequest(
        @Size(max = 100) String name,
        @Positive Integer price
) {
}
