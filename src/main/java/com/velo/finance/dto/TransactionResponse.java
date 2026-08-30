package com.velo.finance.dto;

import com.velo.finance.CategoryKind;
import com.velo.finance.FinanceTransaction;

import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        UUID categoryId,
        CategoryKind kind,
        int amount,
        Instant date,
        String comment,
        UUID rentalId,
        /** Статус аренды операции (completed/completed_early → операция заморожена, замок на фронте). */
        String rentalStatus,
        UUID assetId,
        boolean system
) {
    public static TransactionResponse from(FinanceTransaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccount().getId(),
                transaction.getCategory().getId(),
                transaction.getKind(),
                transaction.getAmount(),
                transaction.getDate(),
                transaction.getComment(),
                transaction.getRental() != null ? transaction.getRental().getId() : null,
                transaction.getRental() != null
                        ? transaction.getRental().getStatus().name().toLowerCase() : null,
                transaction.getAsset() != null ? transaction.getAsset().getId() : null,
                transaction.isSystem());
    }
}
