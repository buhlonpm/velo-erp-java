package com.velo.report;

import com.velo.common.exception.BadRequestException;
import com.velo.finance.CategoryKind;
import com.velo.finance.FinanceTransactionRepository;
import com.velo.finance.SystemCategories;
import com.velo.report.dto.PnlReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Отчёты. P&L — чисто кассовый: агрегация finance_transactions за период,
 * ничего не хранится и не начисляется. Период — календарные даты в локальной
 * зоне сервера, [from, to] включительно.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final FinanceTransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public PnlReportResponse pnl(LocalDate from, LocalDate to, UUID accountId) {
        if (to == null) {
            throw new BadRequestException("Укажите конец периода: to");
        }
        ZoneId zone = ZoneId.systemDefault();
        if (from == null) {
            // «За всё время»: старт от первой операции по кассе; операций нет — пустой день «to»
            Instant minDate = transactionRepository.findMinDate();
            from = minDate != null ? LocalDate.ofInstant(minDate, zone) : to;
        }
        if (from.isAfter(to)) {
            throw new BadRequestException("Дата начала периода позже даты конца");
        }
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        List<Object[]> raw = accountId != null
                ? transactionRepository.pnlByCategoryBetweenAndAccountId(fromInstant, toInstant, accountId)
                : transactionRepository.pnlByCategoryBetween(fromInstant, toInstant);

        List<PnlReportResponse.Row> income = new ArrayList<>();
        List<PnlReportResponse.Row> expense = new ArrayList<>();
        int ownerInvestmentTotal = 0;
        for (Object[] row : raw) {
            UUID categoryId = (UUID) row[0];
            String categoryName = (String) row[1];
            CategoryKind kind = (CategoryKind) row[2];
            int total = ((Number) row[3]).intValue();
            // «Введение денег в бизнес» — не выручка, а вложения владельца: в P&L не входят,
            // выводятся отдельной цифрой (иначе завышают приходы и прибыль)
            if (kind == CategoryKind.INCOME && SystemCategories.OWNER_INVESTMENT.equals(categoryName)) {
                ownerInvestmentTotal += total;
                continue;
            }
            boolean capex = kind == CategoryKind.EXPENSE
                    ? SystemCategories.CAPEX_EXPENSE.contains(categoryName)
                    : SystemCategories.EQUIPMENT_SALE.equals(categoryName);
            (kind == CategoryKind.INCOME ? income : expense)
                    .add(new PnlReportResponse.Row(categoryId, categoryName, total, capex));
        }

        int incomeTotal = income.stream().mapToInt(PnlReportResponse.Row::total).sum();
        int expenseTotal = expense.stream().mapToInt(PnlReportResponse.Row::total).sum();
        int capexIn = income.stream().filter(PnlReportResponse.Row::capex)
                .mapToInt(PnlReportResponse.Row::total).sum();
        int capexOut = expense.stream().filter(PnlReportResponse.Row::capex)
                .mapToInt(PnlReportResponse.Row::total).sum();

        return new PnlReportResponse(from, to, income, expense, incomeTotal, expenseTotal,
                (incomeTotal - capexIn) - (expenseTotal - capexOut),
                capexOut, capexIn, incomeTotal - expenseTotal, ownerInvestmentTotal);
    }
}
