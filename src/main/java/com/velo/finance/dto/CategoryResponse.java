package com.velo.finance.dto;

import com.velo.finance.CategoryKind;
import com.velo.finance.FinanceCategory;

import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        CategoryKind kind,
        /** По статье есть операции — удалить нельзя. */
        boolean inUse
) {
    public static CategoryResponse from(FinanceCategory category, boolean inUse) {
        return new CategoryResponse(category.getId(), category.getName(), category.getKind(), inUse);
    }
}
