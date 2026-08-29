package com.velo.asset.dto;

import com.velo.asset.AssetEvent;

import java.time.Instant;
import java.util.UUID;

public record AssetEventResponse(
        UUID id,
        String type,
        Instant date,
        String comment,
        Integer amount,
        UUID transactionId,
        String createdByName
) {
    public static AssetEventResponse from(AssetEvent event) {
        return new AssetEventResponse(
                event.getId(),
                event.getType().getValue(),
                event.getDate(),
                event.getComment(),
                event.getAmount(),
                event.getTransaction() != null ? event.getTransaction().getId() : null,
                event.getCreatedBy() != null ? event.getCreatedBy().getFullName() : null);
    }
}
