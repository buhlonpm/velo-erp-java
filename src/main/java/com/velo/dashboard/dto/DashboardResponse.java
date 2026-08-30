package com.velo.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Дашборд одним запросом: метрики парка по типам + списки аренд. */
public record DashboardResponse(
        List<TypeStats> assets,
        List<RentalRow> overdue,
        List<RentalRow> endingSoon,
        /** Последние аренды (свежие сверху, до 5). */
        List<RentalRow> latest
) {
    /** Счётчики активов одного типа по статусам. */
    public record TypeStats(
            String type,
            long total,
            long available,
            long mounted,
            long reserved,
            long rented,
            long maintenance
    ) {
    }

    /** Компактная строка аренды для списков дашборда. */
    public record RentalRow(
            UUID id,
            String customerName,
            /** Состав: «Велосипед (EV-001) + 2» */
            String composition,
            Instant startAt,
            Instant plannedEndAt,
            /** Начислено на текущий момент (для выкупа — цена выкупа). */
            int amount,
            /** Статус для отображения (active/overdue/draft/…). */
            String status,
            /** Ближайший непогашенный платёж по графику (rent_to_own); null — rent или всё оплачено. */
            Instant nextPaymentDue
    ) {
    }
}
