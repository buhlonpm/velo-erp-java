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
        /** Срок аренды: plannedEndAt = startAt + duration × durationUnit, считает сервер (rent). */
        @Min(1) Integer duration,
        TariffUnit durationUnit,
        @Min(0) Integer buyoutPrice,
        @Size(max = 2000) String comment,
        @NotEmpty List<@Valid Item> items
) {
    public record Item(
            @NotNull UUID assetId,
            /** Единица тарифа. rent: игнорируется — сервер ставит durationUnit аренды. rent_to_own: обязательна (409). */
            TariffUnit tariffUnit,
            /** Цена за единицу; не передана — 0. В справочник модели НЕ сохраняется. */
            @Min(0) Integer rate
    ) {
    }
}
