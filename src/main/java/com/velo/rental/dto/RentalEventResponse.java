package com.velo.rental.dto;

import com.velo.rental.RentalEvent;

import java.time.Instant;
import java.util.UUID;

public record RentalEventResponse(
        UUID id,
        String type,
        Instant date,
        /** Дата «по документам» (дата оплаты/выдачи/приёма), может отличаться от фактической. */
        Instant docDate,
        String comment,
        Integer amount,
        UUID transactionId,
        /** Продление: на сколько и как сдвинулся конец периода. */
        Integer duration,
        String durationUnit,
        Instant fromEndAt,
        Instant toEndAt,
        String createdByName
) {
    public static RentalEventResponse from(RentalEvent event) {
        return new RentalEventResponse(
                event.getId(),
                event.getType().getValue(),
                event.getDate(),
                event.getDocDate(),
                event.getComment(),
                event.getAmount(),
                event.getTransaction() != null ? event.getTransaction().getId() : null,
                event.getDuration(),
                event.getDurationUnit() != null ? event.getDurationUnit().getValue() : null,
                event.getFromEndAt(),
                event.getToEndAt(),
                event.getCreatedBy() != null ? event.getCreatedBy().getFullName() : null);
    }
}
