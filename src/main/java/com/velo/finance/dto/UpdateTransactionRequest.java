package com.velo.finance.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Правка операции (только с правом finance:manage). Все поля опциональны.
 * Привязка к аренде не редактируется.
 */
public record UpdateTransactionRequest(
        UUID accountId,
        UUID categoryId,
        @Positive Integer amount,
        /** Дата операции (например, корректировка даты платежа по аренде). */
        java.time.Instant date,
        @Size(max = 1000) String comment
) {
}
