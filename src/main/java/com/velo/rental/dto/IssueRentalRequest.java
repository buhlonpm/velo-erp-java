package com.velo.rental.dto;

import java.time.Instant;

/** Выдача аренды-черновика: дата фактической выдачи (не передана — сейчас). */
public record IssueRentalRequest(Instant date) {
}
