package com.velo.gps.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateSimCardRequest(
        @NotBlank @Size(max = 32) String phoneNumber,
        @NotBlank @Size(max = 50) String operator,
        @Size(max = 255) String note,
        /** При отдельной покупке обязателен; в комплекте наследуется от трекера. */
        Instant purchasedAt,
        /** Отдельная покупка — обязательна и > 0; в комплекте — 0/null. */
        @PositiveOrZero Integer purchasePrice,
        /** Обязателен при отдельной покупке — покупка списывается расходной операцией. */
        UUID purchaseAccountId,
        /** «В комплекте с трекером»: цена обязана быть 0, дата покупки наследуется от трекера. */
        UUID bundledTrackerId
) {
}
