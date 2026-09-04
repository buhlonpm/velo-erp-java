package com.velo.finance;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Реестр системных финансовых статей. Они сидятся при старте (DataSeeder),
 * показываются в списке статей, но не удаляются (409). Доменный код получает
 * их через {@link #ensure(String, CategoryKind)} — создание «на лету» оставлено
 * только как страховка, после сидов оно не срабатывает.
 */
@Component
@RequiredArgsConstructor
public class SystemCategories {

    public static final String EQUIPMENT_PURCHASE = "Покупка оборудования";
    public static final String EQUIPMENT_SALE = "Продажа оборудования";
    public static final String RENTAL_PAYMENT = "Оплата аренды";
    public static final String BUYOUT_PAYMENT = "Платёж по выкупу";
    public static final String RENTAL_REFUND = "Возврат по аренде";
    public static final String BUYOUT_REFUND = "Возврат по выкупу";
    public static final String MAINTENANCE = "Обслуживание и ремонт";
    public static final String BIKE_PREPARATION = "Подготовка велосипеда";
    public static final String OWNER_INVESTMENT = "Введение денег в бизнес";

    /** Капекс-статьи расхода (вложения в технику) — в P&L идут отдельно от операционной прибыли. */
    public static final java.util.Set<String> CAPEX_EXPENSE = java.util.Set.of(
            EQUIPMENT_PURCHASE, BIKE_PREPARATION);

    /** Все системные статьи (имя → тип) — порядок важен для сидов. */
    public static final Map<String, CategoryKind> ALL = new LinkedHashMap<>();

    static {
        ALL.put(EQUIPMENT_PURCHASE, CategoryKind.EXPENSE);
        ALL.put(EQUIPMENT_SALE, CategoryKind.INCOME);
        ALL.put(RENTAL_PAYMENT, CategoryKind.INCOME);
        ALL.put(BUYOUT_PAYMENT, CategoryKind.INCOME);
        ALL.put(RENTAL_REFUND, CategoryKind.EXPENSE);
        ALL.put(BUYOUT_REFUND, CategoryKind.EXPENSE);
        ALL.put(MAINTENANCE, CategoryKind.EXPENSE);
        ALL.put(BIKE_PREPARATION, CategoryKind.EXPENSE);
        ALL.put(OWNER_INVESTMENT, CategoryKind.INCOME);
    }

    private final FinanceCategoryRepository repository;

    /** Найти системную статью по имени; если вдруг нет — создать с флагом system. */
    public FinanceCategory ensure(String name, CategoryKind kind) {
        return repository.findByNameAndKind(name, kind)
                .orElseGet(() -> {
                    FinanceCategory created = new FinanceCategory();
                    created.setName(name);
                    created.setKind(kind);
                    created.setSystem(true);
                    return repository.save(created);
                });
    }
}
