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
import com.velo.rental.RentalRepository;
import com.velo.rental.RentalStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Агрегация данных дашборда: считается на лету, ничего не хранится. */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Доля оставшегося срока, при которой аренда считается «подходящей к концу». */
    private static final double ENDING_SOON_THRESHOLD = 0.2;

    /** Сколько последних аренд отдавать на дашборд. */
    private static final int LATEST_LIMIT = 5;

    private final AssetRepository assetRepository;
    private final RentalRepository rentalRepository;
    private final FinanceTransactionRepository financeTransactionRepository;

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        Instant now = Instant.now();

        List<Asset> assets = assetRepository.findAll();
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

        List<DashboardResponse.RentalRow> overdue = active.stream()
                .filter(rental -> rental.getPlannedEndAt() != null && rental.getPlannedEndAt().isBefore(now))
                // самые просроченные сверху
                .sorted(Comparator.comparing(Rental::getPlannedEndAt))
                .map(rental -> toRow(rental, now))
                .toList();

        List<DashboardResponse.RentalRow> endingSoon = active.stream()
                .filter(rental -> isEndingSoon(rental, now))
                // раньше заканчивается — выше
                .sorted(Comparator.comparing(Rental::getPlannedEndAt))
                .map(rental -> toRow(rental, now))
                .toList();

        List<DashboardResponse.RentalRow> latest = all.stream()
                .limit(LATEST_LIMIT)
                .map(rental -> toRow(rental, now))
                .toList();

        return new DashboardResponse(stats, overdue, endingSoon, latest);
    }

    /** Аренда «подходит к концу»: осталось меньше 20% всего срока (и не просрочена). */
    private boolean isEndingSoon(Rental rental, Instant now) {
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

    private DashboardResponse.RentalRow toRow(Rental rental, Instant now) {
        // завершённая аренда — сумма по деньгам (оплачено − возвращено), остальные — начисленное
        int amount = RentalAmounts.isFinished(rental)
                ? financeTransactionRepository.paidSumByRentalId(rental.getId())
                        - financeTransactionRepository.refundedSumByRentalId(rental.getId())
                : RentalAmounts.accrued(rental, now);
        return new DashboardResponse.RentalRow(
                rental.getId(),
                rental.getCustomer().getFullName(),
                composition(rental),
                rental.getStartAt(),
                rental.getPlannedEndAt(),
                amount,
                rental.displayStatus(now));
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
