package com.velo.rental.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Правка аренды. Пока только сумма выкупа (rent_to_own, черновик/активная). */
public record UpdateRentalRequest(
        @NotNull @Min(1) Integer buyoutPrice
) {
}
