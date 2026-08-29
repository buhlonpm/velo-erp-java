package com.velo.finance.dto;

import com.velo.finance.FinanceAccount;

import java.util.UUID;

/** Облегчённый счёт для селектов (без остатка) — доступен всем сотрудникам. */
public record AccountOptionResponse(UUID id, String name) {

    public static AccountOptionResponse from(FinanceAccount account) {
        return new AccountOptionResponse(account.getId(), account.getName());
    }
}
