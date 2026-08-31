package com.velo.rental.dto;

import com.velo.rental.dto.CreateRentalRequest.Item;
import com.velo.tariff.TariffUnit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Правка аренды-ЧЕРНОВИКА: любые поля (все опциональны, null = не менять).
 * У выданной/завершённой аренды — 409: после выдачи условия договора фиксируются.
 * Тип договора (kind) не меняется — отмена черновика + новый.
 */
public record UpdateRentalRequest(
        UUID customerId,
        Instant startAt,
        @Size(max = 2000) String comment,
        /** rent: новый срок (количество + единица, парой) — plannedEndAt пересчитывается. */
        @Min(1) Integer duration,
        TariffUnit durationUnit,
        /** rent_to_own: срок выкупа (13/26/52) и цена выкупа (не ниже оплаченного). */
        Integer termWeeks,
        @Min(1) Integer buyoutPrice,
        /** Полная замена позиций (как при создании; комплект подтягивается автоматом с 0 ₽). */
        List<@Valid Item> items
) {
}
