package com.velo.rental;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * График платежей договора «под выкуп». Покрытие строк ХРАНИТСЯ (covered_amount) и разносится
 * платежами по порядку (FIFO). Это важно: при перестройке графика (стратегии переплаты, скидка)
 * переплата «поглощается» перестройкой — новые строки стартуют с нулевым покрытием, и тогда
 * Σ(сумма − покрытие) = buyoutPrice − оплачено: итог договора неизменен при любой стратегии.
 * Поглощённая сумма накапливается в rentals.schedule_absorbed, и FIFO-разнесение ВСЕГДА идёт
 * от «оплачено − absorbed»: иначе поглощённые деньги повторно гасили бы новые строки (график
 * показывал «всё оплачено» до полной выплаты суммы выкупа). Перестройка пересчитывает absorbed
 * как «оплачено − сумма сохранённых строк». Правка/удаление оплат перераспределяет покрытие
 * заново (FIFO) — перестроения при этом НЕ откатываются.
 */
public final class RentalSchedule {

    /** Платёжный шаг графика — неделя. */
    public static final long STEP_DAYS = 7;

    private RentalSchedule() {
    }

    /** Статус строки графика для отображения. */
    public enum RowStatus {
        PAID, PARTIAL, NEXT, PENDING, OVERDUE
    }

    public record RowState(RentalScheduleItem item, RowStatus status) {
    }

    /** Статус каждой строки по хранимому покрытию. Просрочка — по календарному дню
     *  (локальная дата сервера): платёж «на сегодня» не просрочен. */
    public static List<RowState> states(List<RentalScheduleItem> items, Instant now) {
        LocalDate today = now.atZone(ZoneId.systemDefault()).toLocalDate();
        List<RowState> result = new ArrayList<>();
        boolean nextMarked = false;
        for (RentalScheduleItem item : items) {
            boolean pastDue = item.getDueDate().atZone(ZoneId.systemDefault())
                    .toLocalDate().isBefore(today);
            RowStatus status;
            if (item.getCoveredAmount() >= item.getAmount()) {
                status = RowStatus.PAID;
            } else if (pastDue) {
                status = RowStatus.OVERDUE;
            } else if (item.getCoveredAmount() > 0) {
                status = RowStatus.PARTIAL;
            } else if (!nextMarked) {
                status = RowStatus.NEXT;
                nextMarked = true;
            } else {
                status = RowStatus.PENDING;
            }
            result.add(new RowState(item, status));
        }
        return result;
    }

    /** Просрочка: есть непогашенная строка с датой в прошлом. */
    public static boolean isOverdue(List<RentalScheduleItem> items, Instant now) {
        return states(items, now).stream().anyMatch(state -> state.status() == RowStatus.OVERDUE);
    }

    /** Дата ближайшего непогашенного платежа (null — всё оплачено). */
    public static Instant nextPaymentDue(List<RentalScheduleItem> items) {
        return items.stream()
                .filter(item -> item.getCoveredAmount() < item.getAmount())
                .map(RentalScheduleItem::getDueDate)
                .findFirst()
                .orElse(null);
    }

    /** FIFO-разнесение бюджета по строкам графика (после приёма/правки/удаления оплат).
     *  Бюджет = оплачено − rentals.schedule_absorbed (поглощённую перестройками переплату
     *  заново разносить нельзя — она уже «съедена» сокращением срока/платежей). */
    public static void allocate(List<RentalScheduleItem> items, int paidTotal) {
        int budget = paidTotal;
        for (RentalScheduleItem item : items) {
            int covered = Math.min(item.getAmount(), Math.max(0, budget));
            item.setCoveredAmount(covered);
            budget -= covered;
        }
    }

    /**
     * Перестройка «хвоста» графика после переплаты или изменения суммы выкупа.
     * Историей остаются только ПОЛНОСТЬЮ погашенные строки, чья неделя уже наступила
     * (по календарному дню от now). Остальные строки заменяются новыми с нулевым покрытием
     * на сумму R = buyoutPrice − оплачено (переплата «поглощается» — именно это укорачивает
     * срок или уменьшает платежи без изменения итога договора). Побочный эффект: обновляет
     * rentals.schedule_absorbed = оплачено − сумма сохранённых строк.
     * shorten — недельный платёж прежний, платежи идут со следующей недели, конец ближе;
     * reduce  — слоты прежние (даты не трогаем), платёж = R / число оставшихся слотов.
     */
    public static void rebuildTail(Rental rental, int paidAmount, OverpaymentStrategy strategy,
                                   Instant now) {
        List<RentalScheduleItem> items = rental.getScheduleItems();
        LocalDate today = now.atZone(ZoneId.systemDefault()).toLocalDate();
        List<RentalScheduleItem> kept = new ArrayList<>();
        List<RentalScheduleItem> tail = new ArrayList<>();
        for (RentalScheduleItem item : items) {
            boolean arrived = !item.getDueDate().atZone(ZoneId.systemDefault())
                    .toLocalDate().isAfter(today);
            if (item.getCoveredAmount() >= item.getAmount() && arrived) {
                kept.add(item);
            } else {
                tail.add(item);
            }
        }
        int remainingToPay = rental.getBuyoutPrice() - paidAmount;
        // поглощённая переплата = оплачено − сумма сохранённых строк: эти деньги уже «съедены»
        // перестройкой и не должны заново гасить новые строки при следующих allocate
        int keptTotal = kept.stream().mapToInt(RentalScheduleItem::getAmount).sum();
        rental.setScheduleAbsorbed(paidAmount - keptTotal);
        items.clear();
        items.addAll(kept);
        if (remainingToPay <= 0 || tail.isEmpty()) {
            if (!items.isEmpty()) {
                rental.setPlannedEndAt(items.get(items.size() - 1).getDueDate());
            }
            return;
        }

        int count;
        Instant firstDue;
        List<Integer> amounts = new ArrayList<>();
        if (strategy == OverpaymentStrategy.SHORTEN_TERM) {
            int weekly = tail.get(0).getAmount();
            count = (remainingToPay + weekly - 1) / weekly;
            // следующая неделя недельной сетки от начала (если наступивших погашенных нет —
            // исходная дата первой строки: оплата до выдачи сетку не сдвигает)
            firstDue = kept.isEmpty()
                    ? tail.get(0).getDueDate()
                    : rental.getStartAt().plus(
                            (Math.max(0, java.time.Duration.between(rental.getStartAt(), now)
                                    .toDays() / STEP_DAYS) + 1) * STEP_DAYS, ChronoUnit.DAYS);
            for (int i = 0; i < count - 1; i++) {
                amounts.add(weekly);
            }
            // последний платёж добирает остаток (1..weekly)
            amounts.add(remainingToPay - weekly * (count - 1));
        } else {
            // REDUCE_NEXT: остаток поровну по прежним слотам; копеечный остаток — в последний.
            // Если остаток меньше числа слотов — строк меньше (каждая минимум 1 ₽).
            count = Math.min(tail.size(), remainingToPay);
            firstDue = tail.get(0).getDueDate();
            int base = remainingToPay / count;
            for (int i = 0; i < count; i++) {
                amounts.add(base);
            }
            amounts.set(count - 1, amounts.get(count - 1) + remainingToPay % count);
        }
        int firstSeq = kept.size() + 1;
        for (int i = 0; i < count; i++) {
            RentalScheduleItem item = new RentalScheduleItem();
            item.setRental(rental);
            item.setSeq(firstSeq + i);
            item.setDueDate(firstDue.plus(STEP_DAYS * i, ChronoUnit.DAYS));
            item.setAmount(amounts.get(i));
            items.add(item);
        }
        // конец договора = дата последнего платежа
        rental.setPlannedEndAt(items.get(items.size() - 1).getDueDate());
    }

    /**
     * Полный пересчёт графика с нуля (как в кредитных системах): регенерируем исходный график
     * от текущих условий (цена выкупа × срок от startAt) и заново проигрываем все оплаты
     * в хронологии — каждая со своей стратегией переплаты (хранится на операции). Вызывается
     * на приём/правку/удаление оплаты и на правку условий черновика: удалённая ошибочная
     * оплата выпадает из истории, и график возвращается к виду «как будто её не было».
     * Детерминировано: rebuildTail считает от даты платежа, а она хранится в операции.
     */
    public static void replay(Rental rental, List<com.velo.finance.FinanceTransaction> payments) {
        rental.getScheduleItems().clear();
        generate(rental, rental.getTermWeeks(), rental.getBuyoutPrice(), rental.getStartAt());
        rental.setScheduleAbsorbed(0);
        int paid = 0;
        for (com.velo.finance.FinanceTransaction payment : payments) {
            paid += payment.getAmount();
            allocate(rental.getScheduleItems(), paid - rental.getScheduleAbsorbed());
            if (payment.getOverpaymentStrategy() != null) {
                rebuildTail(rental, paid, payment.getOverpaymentStrategy(), payment.getDate());
            }
        }
        if (!rental.getScheduleItems().isEmpty()) {
            rental.setPlannedEndAt(rental.getScheduleItems()
                    .get(rental.getScheduleItems().size() - 1).getDueDate());
        }
    }

    /** Генерация графика при создании: N платежей от даты начала, первый — в день начала. */
    public static void generate(Rental rental, int termWeeks, int buyoutPrice, Instant startAt) {
        int weekly = buyoutPrice / termWeeks;
        for (int i = 0; i < termWeeks; i++) {
            RentalScheduleItem item = new RentalScheduleItem();
            item.setRental(rental);
            item.setSeq(i + 1);
            item.setDueDate(startAt.plus(STEP_DAYS * i, ChronoUnit.DAYS));
            // округление уходит в последний платёж — сумма строк равна buyoutPrice
            item.setAmount(i < termWeeks - 1 ? weekly : buyoutPrice - weekly * (termWeeks - 1));
            rental.getScheduleItems().add(item);
        }
    }
}
