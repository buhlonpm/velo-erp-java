package com.velo.rental;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Суммы аренды. Начисленное — всегда фиксированная сумма периода (startAt → plannedEndAt)
 * по тарифам позиций, в любом статусе: просрочка деньгами не досчитывается (просроченную
 * аренду продлевают или завершают в день окончания). Для завершённой (completed /
 * completed_early) сумма = оплачено − возвращено: фиксируется деньгами (операции по
 * завершённой аренде заморожены, число не уползает), невозвращённая переплата при досрочном
 * возврате остаётся выручкой. По позициям разносится пропорционально начисленному по корневым
 * позициям (комплектные позиции — всегда 0).
 */
public final class RentalAmounts {

    private RentalAmounts() {
    }

    public static boolean isFinished(Rental rental) {
        return rental.getStatus() == RentalStatus.COMPLETED
                || rental.getStatus() == RentalStatus.COMPLETED_EARLY;
    }

    /** Начисленное по тарифам: фиксированная сумма периода аренды (rent_to_own — цена выкупа). */
    public static int accrued(Rental rental, Instant at) {
        return rental.getKind() == RentalKind.RENT_TO_OWN
                ? (rental.getBuyoutPrice() != null ? rental.getBuyoutPrice() : 0)
                : rental.getItems().stream().mapToInt(item -> item.amount(at)).sum();
    }

    /**
     * Начисленное за фактический срок startAt → at, БЕЗ доплаты до конца периода
     * (досрочный возврат: переплата = оплачено − accruedActual).
     */
    public static int accruedActual(Rental rental, Instant at) {
        return rental.getKind() == RentalKind.RENT_TO_OWN
                ? (rental.getBuyoutPrice() != null ? rental.getBuyoutPrice() : 0)
                : rental.getItems().stream().mapToInt(item -> item.amountForPeriod(at)).sum();
    }

    /** Итоговая сумма аренды: завершённая — оплачено − возвращено, иначе начисленное. */
    public static int total(Rental rental, Instant at, int paidAmount, int refundedAmount) {
        return isFinished(rental) ? paidAmount - refundedAmount : accrued(rental, at);
    }

    /** Суммы по позициям (ключ — id позиции); сумма значений = total(...). */
    public static Map<UUID, Integer> itemAmounts(Rental rental, Instant at, int paidAmount, int refundedAmount) {
        Map<UUID, Integer> result = new LinkedHashMap<>();
        List<RentalItem> items = rental.getItems();
        if (!isFinished(rental)) {
            items.forEach(item -> result.put(item.getId(), item.amount(at)));
            return result;
        }
        int finalTotal = paidAmount - refundedAmount;
        List<RentalItem> roots = items.stream().filter(item -> item.getParentItem() == null).toList();
        items.stream().filter(item -> item.getParentItem() != null)
                .forEach(item -> result.put(item.getId(), 0));
        int accruedTotal = roots.stream().mapToInt(item -> item.amount(at)).sum();
        if (roots.isEmpty() || accruedTotal <= 0 || finalTotal == accruedTotal) {
            roots.forEach(item -> result.put(item.getId(), item.amount(at)));
            return result;
        }
        // пропорция: доля позиции в деньгах = её доле в начисленном; остаток — на самую дорогую
        RentalItem largest = roots.stream()
                .max(Comparator.comparingInt(item -> item.amount(at)))
                .orElseThrow();
        int distributed = 0;
        for (RentalItem item : roots) {
            if (item == largest) {
                continue;
            }
            int share = (int) ((long) item.amount(at) * finalTotal / accruedTotal);
            result.put(item.getId(), share);
            distributed += share;
        }
        result.put(largest.getId(), finalTotal - distributed);
        return result;
    }
}
