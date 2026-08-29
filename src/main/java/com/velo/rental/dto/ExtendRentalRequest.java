package com.velo.rental.dto;

import com.velo.tariff.TariffUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Продление аренды: якорь = max(plannedEndAt, сейчас), новый конец = якорь + duration × unit.
 * Оплаты при продлении больше нет — платежи принимаются отдельно через /payments.
 */
public record ExtendRentalRequest(
        @NotNull @Min(1) Integer duration,
        @NotNull TariffUnit durationUnit
) {
}
