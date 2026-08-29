package com.velo.finance.dto;

import com.velo.finance.AccountType;
import com.velo.finance.FinanceAccount;

import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        AccountType type,
        /** Баланс = сумма приходов − сумма расходов. Не хранится, вычисляется. */
        int balance,
        /** По счёту есть операции — удалить нельзя. */
        boolean inUse
) {
    public static AccountResponse from(FinanceAccount account, int balanceDelta, boolean inUse) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getType(),
                balanceDelta,
                inUse);
    }
}
