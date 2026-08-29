package com.velo.rental.dto;

import jakarta.validation.constraints.Min;

import java.time.Instant;
import java.util.UUID;

/** Возврат позиции; опционально — дата приёма и возврат денег клиенту (расходная операция + событие refund). */
public record ReturnItemRequest(
        @Min(0) Integer refundAmount,
        /** Счёт, с которого вернули деньги; обязателен при refundAmount > 0. */
        UUID refundAccountId,
        /** Дата фактического приёма (не передана — сейчас). */
        Instant date
) {
}
