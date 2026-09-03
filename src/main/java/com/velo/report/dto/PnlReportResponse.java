package com.velo.report.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Кассовый P&L за период: приходы и расходы по статьям + итоги.
 * Капекс (покупка/продажа оборудования — системные статьи) помечен флагом capex
 * и вынесен в отдельные итоги: operatingProfit — без него, netProfit — с ним.
 */
public record PnlReportResponse(
        LocalDate from,
        LocalDate to,
        List<Row> income,
        List<Row> expense,
        int incomeTotal,
        int expenseTotal,
        /** Операционная прибыль: без покупки/продажи техники. */
        int operatingProfit,
        /** Вложено в технику за период (покупка оборудования). */
        int capexOut,
        /** Выручено за технику за период (продажа оборудования). */
        int capexIn,
        /** Итог с капексом: incomeTotal − expenseTotal. */
        int netProfit,
        /** Вложения владельца («Введение денег в бизнес») — в приходы и прибыль НЕ входят. */
        int ownerInvestmentTotal) {

    public record Row(UUID categoryId, String categoryName, int total, boolean capex) {
    }
}
