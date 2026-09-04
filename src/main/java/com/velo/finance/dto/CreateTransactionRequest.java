package com.velo.finance.dto;

import com.velo.finance.CategoryKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull UUID accountId,
        @NotNull UUID categoryId,
        @NotNull CategoryKind kind,
        @Positive int amount,
        @Size(max = 1000) String comment,
        /** Дата операции (по умолчанию — сейчас; не в будущем). Опционально. */
        java.time.Instant date,
        /** Привязка к аренде (оплата аренды / выкупной платёж). Опционально. */
        UUID rentalId,
        /** Привязка к активу (ремонт, выплата за повреждение). Опционально. */
        UUID assetId
) {
}
