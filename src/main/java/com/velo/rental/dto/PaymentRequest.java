package com.velo.rental.dto;

import com.velo.rental.OverpaymentStrategy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** Приём оплаты по аренде (платежей может быть несколько, в черновике и после выдачи). */
public record PaymentRequest(
        @NotNull @Min(1) Integer amount,
        @NotNull UUID accountId,
        /** Дата платежа; не передана — сейчас. */
        Instant date,
        /** Стратегия переплаты (только rent_to_own); null — переплата гасит ближайшие платежи. */
        OverpaymentStrategy overpaymentStrategy
) {
}
