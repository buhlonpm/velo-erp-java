package com.velo.rental.dto;

import java.time.Instant;

/** Обычное завершение аренды: дата приёма техники (не передана — сейчас). ±24 часа от конца аренды. */
public record CompleteRentalRequest(Instant date) {
}
