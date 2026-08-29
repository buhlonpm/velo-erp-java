package com.velo.asset.dto;

import com.velo.asset.WriteOffReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Выбытие актива. reason=sold требует salePrice + saleAccountId (приходный ордер).
 */
public record WriteOffAssetRequest(
        @NotNull WriteOffReason reason,
        @Positive Integer salePrice,
        UUID saleAccountId,
        @Size(max = 1000) String comment
) {
}
