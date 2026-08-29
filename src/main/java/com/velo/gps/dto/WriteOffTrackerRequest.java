package com.velo.gps.dto;

import com.velo.asset.WriteOffReason;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Выбытие трекера: причина + комментарий. Потеря — флотская.
 * reason=sold требует salePrice + saleAccountId (приходная операция).
 */
public record WriteOffTrackerRequest(
        WriteOffReason reason,
        @Size(max = 1000) String comment,
        @Positive Integer salePrice,
        UUID saleAccountId
) {
}
