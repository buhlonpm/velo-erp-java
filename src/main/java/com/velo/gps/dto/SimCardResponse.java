package com.velo.gps.dto;

import com.velo.gps.SimCard;

import java.time.Instant;
import java.util.UUID;

public record SimCardResponse(
        UUID id,
        String phoneNumber,
        String operator,
        String note,
        Instant purchasedAt,
        Integer purchasePrice,
        String status,
        String writeOffReason,
        /** Трекер, в который вставлена симка (NULL — свободна). */
        UUID trackerId
) {
    public static SimCardResponse from(SimCard simCard, UUID trackerId) {
        return new SimCardResponse(
                simCard.getId(),
                simCard.getPhoneNumber(),
                simCard.getOperator(),
                simCard.getNote(),
                simCard.getPurchasedAt(),
                simCard.getPurchasePrice(),
                simCard.getStatus() == com.velo.gps.SimCardStatus.ACTIVE ? "active" : "written_off",
                simCard.getWriteOffReason() != null ? simCard.getWriteOffReason().getValue() : null,
                trackerId);
    }
}
