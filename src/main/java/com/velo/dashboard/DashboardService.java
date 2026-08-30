package com.velo.dashboard;

import com.velo.asset.Asset;
import com.velo.asset.AssetRepository;
import com.velo.asset.AssetStatus;
import com.velo.asset.AssetType;
import com.velo.dashboard.dto.DashboardResponse;
import com.velo.finance.FinanceTransactionRepository;
import com.velo.rental.Rental;
import com.velo.rental.RentalAmounts;
import com.velo.rental.RentalItem;
import com.velo.rental.RentalKind;
import com.velo.rental.RentalRepository;
import com.velo.rental.RentalSchedule;
import com.velo.rental.RentalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Агрегация данных дашборда: считается на лету, ничего не хранится. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Доля оставшегося срока, при которой аренда считается «подходящей к концу». */
    private static final double ENDING_SOON_THRESHOLD = 0.2;

    /** Для выкупа «к оплате скоро»: до ближайшего платежа по графику осталось меньше этого. */
    private static final Duration PAYMENT_DUE_SOON = Duration.ofDays(2);

    /** Сколько последних аренд отдавать на дашборд. */
    private static final int LATEST_LIMIT = 5;

    private final AssetRepository assetRepository;
    private final RentalRepository rentalRepository;
    private final FinanceTransactionRepository financeTransactionRepository;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        Instant now = Instant.now();

        // выбывшие (проданные/списанные/выкупленные) в метриках парка не участвуют
        List<Asset> assets = assetRepository.findAll().stream()
                .filter(a -> a.getStatus() != AssetStatus.SOLD
                        && a.getStatus() != AssetStatus.DECOMMISSIONED
                        && a.getStatus() != AssetStatus.BOUGHT_OUT)
                .toList();
        List<DashboardResponse.TypeStats> stats = Arrays.stream(AssetType.values())
                .map(type -> {
                    List<Asset> ofType = assets.stream().filter(a -> a.getType() == type).toList();
                    return new DashboardResponse.TypeStats(
                            type.getValue(),
                            ofType.size(),
                            count(ofType, AssetStatus.AVAILABLE),
                            count(ofType, AssetStatus.MOUNTED),
                            count(ofType, AssetStatus.RESERVED),
                            count(ofType, AssetStatus.RENTED),
                            count(ofType, AssetStatus.MAINTENANCE));
                })
                .toList();

        List<Rental> all = rentalRepository.findAllByOrderByCreatedAtDesc();
        List<Rental> active = all.stream()
                .filter(rental -> rental.getStatus() == RentalStatus.ACTIVE)
                .toList();

        // у выкупа просрочка — по графику платежей, у аренды — по концу периода
        List<DashboardResponse.RentalRow> overdue = active.stream()
                .filter(rental -> isOverdue(rental, now))
                // самые просроченные сверху
                .sorted(Comparator.comparing(rental -> overdueSince(rental, now)))
                .map(rental -> toRow(rental, now))
                .toList();

        List<DashboardResponse.RentalRow> endingSoon = active.stream()
                .filter(rental -> !isOverdue(rental, now) && isEndingSoon(rental, now))
                // раньше заканчивается / раньше платёж — выше
                .sorted(Comparator.comparing(rental -> soonKey(rental, now)))
                .map(rental -> toRow(rental, now))
                .toList();

        List<DashboardResponse.RentalRow> latest = all.stream()
                .limit(LATEST_LIMIT)
                .map(rental -> toRow(rental, now))
                .toList();

        return new DashboardResponse(stats, overdue, endingSoon, latest);
    }

    /** Просрочка: rent — конец периода в прошлом; rent_to_own — непогашенный платёж в прошлом. */
    private boolean isOverdue(Rental rental, Instant now) {
        if (rental.getKind() == RentalKind.RENT_TO_OWN) {
            return RentalSchedule.isOverdue(rental.getScheduleItems(), now);
        }
        return rental.getPlannedEndAt() != null && rental.getPlannedEndAt().isBefore(now);
    }

    /** Момент, с которого аренда просрочена (для сортировки): конец периода или дата платежа. */
    private Instant overdueSince(Rental rental, Instant now) {
        if (rental.getKind() == RentalKind.RENT_TO_OWN) {
            Instant next = RentalSchedule.nextPaymentDue(rental.getScheduleItems());
            return next != null ? next : now;
        }
        return rental.getPlannedEndAt();
    }

    /** «Подходит к концу» (rent) или «к оплате скоро» (rent_to_own: платёж сегодня/завтра/послезавтра).
     *  Сравнение по календарным дням (локальная дата сервера), как и просрочка по графику. */
    private boolean isEndingSoon(Rental rental, Instant now) {
        if (rental.getKind() == RentalKind.RENT_TO_OWN) {
            Instant next = RentalSchedule.nextPaymentDue(rental.getScheduleItems());
            if (next == null) {
                return false;
            }
            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
            java.time.LocalDate dueDay = next.atZone(zone).toLocalDate();
            java.time.LocalDate horizon = now.atZone(zone).toLocalDate()
                    .plusDays(PAYMENT_DUE_SOON.toDays());
            return !dueDay.isAfter(horizon);
        }
        Instant end = rental.getPlannedEndAt();
        if (end == null || !end.isAfter(now)) {
            return false;
        }
        Duration total = Duration.between(rental.getStartAt(), end);
        if (total.isZero() || total.isNegative()) {
            return false;
        }
        Duration remaining = Duration.between(now, end);
        return remaining.toMillis() < total.toMillis() * ENDING_SOON_THRESHOLD;
    }

    /** Ключ сортировки «подходящих»: для выкупа — ближайший платёж, для аренды — конец периода. */
    private Instant soonKey(Rental rental, Instant now) {
        if (rental.getKind() == RentalKind.RENT_TO_OWN) {
            Instant next = RentalSchedule.nextPaymentDue(rental.getScheduleItems());
            return next != null ? next : now.plus(365, ChronoUnit.DAYS);
        }
        return rental.getPlannedEndAt();
    }

    private DashboardResponse.RentalRow toRow(Rental rental, Instant now) {
        // завершённая аренда — сумма по деньгам (оплачено − возвращено), остальные — начисленное
        boolean finished = RentalAmounts.isFinished(rental);
        int paid = finished ? financeTransactionRepository.paidSumByRentalId(rental.getId()) : 0;
        int amount = finished
                ? paid - financeTransactionRepository.refundedSumByRentalId(rental.getId())
                : RentalAmounts.accrued(rental, now);
        Instant nextPaymentDue = rental.getKind() == RentalKind.RENT_TO_OWN && !finished
                ? RentalSchedule.nextPaymentDue(rental.getScheduleItems())
                : null;
        return new DashboardResponse.RentalRow(
                rental.getId(),
                rental.getCustomer().getFullName(),
                composition(rental),
                rental.getStartAt(),
                rental.getPlannedEndAt(),
                amount,
                rental.displayStatus(now),
                nextPaymentDue);
    }

    /** Состав аренды: «EV-001 (EV-001) + 2». */
    private String composition(Rental rental) {
        if (rental.getItems().isEmpty()) {
            return "—";
        }
        RentalItem first = rental.getItems().get(0);
        String label = first.getAsset().getName() + " (" + first.getAsset().getInventoryNumber() + ")";
        int rest = rental.getItems().size() - 1;
        return rest > 0 ? label + " + " + rest : label;
    }

    private static long count(List<Asset> assets, AssetStatus status) {
        return assets.stream().filter(a -> a.getStatus() == status).count();
    }
}
