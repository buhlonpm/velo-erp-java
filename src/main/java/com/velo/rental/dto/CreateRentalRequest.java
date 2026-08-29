package com.velo.rental.dto;

import com.velo.rental.RentalKind;
import com.velo.tariff.TariffUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateRentalRequest(
        @NotNull UUID customerId,
        RentalKind kind,
        Instant startAt,
        Instant plannedEndAt,
        @Min(0) Integer deposit,
        @Min(0) Integer buyoutPrice,
        @Size(max = 2000) String comment,
        @NotEmpty List<@Valid Item> items
) {
    public record Item(
            @NotNull UUID assetId,
            /** Единица тарифа; не передана — hour. */
            TariffUnit tariffUnit,
            /** Цена за единицу; не передана — тариф актива. */
            @Min(0) Integer rate
    ) {
    }
}
