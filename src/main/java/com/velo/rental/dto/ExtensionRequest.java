package com.velo.rental.dto;

import com.velo.tariff.TariffUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Правка продления: новые длительность и единица; срок пересчитывается по цепочке продлений. */
public record ExtensionRequest(
        @NotNull @Min(1) Integer duration,
        @NotNull TariffUnit durationUnit
) {
}
