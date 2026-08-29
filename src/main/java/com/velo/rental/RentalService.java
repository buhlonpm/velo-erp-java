package com.velo.rental;

import com.velo.asset.Asset;
import com.velo.asset.AssetRepository;
import com.velo.asset.AssetStatus;
import com.velo.asset.BatteryAsset;
import com.velo.asset.BikeAsset;
import com.velo.asset.ChargerAsset;
import com.velo.common.exception.ConflictException;
import com.velo.common.exception.NotFoundException;
import com.velo.customer.Customer;
import com.velo.customer.CustomerRepository;
import com.velo.finance.FinanceTransactionRepository;
import com.velo.rental.dto.CreateRentalRequest;
import com.velo.rental.dto.RentalResponse;
import com.velo.tariff.TariffUnit;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RentalService {

    private final RentalRepository rentalRepository;
    private final CustomerRepository customerRepository;
    private final AssetRepository assetRepository;
    private final FinanceTransactionRepository financeTransactionRepository;

    @Transactional(readOnly = true)
    public List<RentalResponse> findAll(String status) {
        Instant now = Instant.now();
        return rentalRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(rental -> toResponse(rental, now))
                .filter(response -> status == null || response.status().equals(status))
                .toList();
    }

    @Transactional(readOnly = true)
    public RentalResponse findById(UUID id) {
        return toResponse(findRental(id), Instant.now());
    }

    @Transactional
    public RentalResponse create(CreateRentalRequest request) {
        RentalKind kind = request.kind() != null ? request.kind() : RentalKind.RENT;
        if (kind == RentalKind.RENT && request.plannedEndAt() == null) {
            throw new ConflictException("Для аренды нужна дата планового окончания");
        }
        if (kind == RentalKind.RENT_TO_OWN && request.buyoutPrice() == null) {
            throw new ConflictException("Для выкупа нужна цена выкупа");
        }

        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new NotFoundException("Клиент не найден"));

        Rental rental = new Rental();
        rental.setCustomer(customer);
        rental.setKind(kind);
        rental.setStartAt(request.startAt() != null ? request.startAt() : Instant.now());
        rental.setPlannedEndAt(request.plannedEndAt());
        rental.setDeposit(request.deposit() != null ? request.deposit() : 0);
        rental.setBuyoutPrice(request.buyoutPrice());
        rental.setComment(request.comment() != null ? request.comment() : "");

        for (CreateRentalRequest.Item itemRequest : request.items()) {
            Asset asset = assetRepository.findById(itemRequest.assetId())
                    .orElseThrow(() -> new NotFoundException("Актив не найден: " + itemRequest.assetId()));
            if (asset.getStatus() != AssetStatus.AVAILABLE) {
                throw new ConflictException("Актив недоступен: " + asset.getName()
                        + " (" + asset.getInventoryNumber() + ")");
            }
            RentalItem item = new RentalItem();
            item.setRental(rental);
            item.setAsset(asset);
            item.setTariffUnit(itemRequest.tariffUnit() != null ? itemRequest.tariffUnit() : TariffUnit.HOUR);
            item.setRate(itemRequest.rate() != null ? itemRequest.rate() : 0);
            rental.getItems().add(item);
            asset.setStatus(AssetStatus.RENTED);
        }

        // авто-комплект: АКБ, смонтированные на велосипедах из позиций, подтягиваются
        // как дочерние позиции (тариф 0, если не переданы явно)
        Map<UUID, RentalItem> itemByAsset = new LinkedHashMap<>();
        rental.getItems().forEach(item -> itemByAsset.put(item.getAsset().getId(), item));
        for (RentalItem parent : List.copyOf(rental.getItems())) {
            if (!(parent.getAsset() instanceof BikeAsset bike)) {
                continue;
            }
            for (BatteryAsset battery : assetRepository.findAllBatteriesByBikeId(bike.getId())) {
                RentalItem child = itemByAsset.get(battery.getId());
                if (child == null) {
                    // смонтированная АКБ едет в аренду вместе с велосипедом
                    if (battery.getStatus() != AssetStatus.AVAILABLE
                            && battery.getStatus() != AssetStatus.MOUNTED) {
                        throw new ConflictException("АКБ велосипеда недоступна: "
                                + battery.getName() + " (" + battery.getInventoryNumber() + ")");
                    }
                    child = new RentalItem();
                    child.setRental(rental);
                    child.setAsset(battery);
                    child.setTariffUnit(TariffUnit.HOUR);
                    child.setRate(0);
                    rental.getItems().add(child);
                    itemByAsset.put(battery.getId(), child);
                    battery.setStatus(AssetStatus.RENTED);
                }
                child.setParentItem(parent);
            }
        }

        return toResponse(rentalRepository.save(rental), Instant.now());
    }

    /** Возврат одной позиции. Все позиции возвращены → аренда завершена. */
    @Transactional
    public RentalResponse returnItem(UUID rentalId, UUID itemId) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("Аренда уже завершена");
        }
        RentalItem item = rental.getItems().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Позиция не найдена в этой аренде"));
        if (item.getReturnedAt() != null) {
            throw new ConflictException("Позиция уже возвращена");
        }

        item.setReturnedAt(Instant.now());
        release(item.getAsset());

        // вернули родителя — возвращаются и дочерние позиции комплекта
        if (item.getParentItem() == null) {
            rental.getItems().stream()
                    .filter(i -> i.getParentItem() != null
                            && i.getParentItem().getId().equals(itemId)
                            && i.getReturnedAt() == null)
                    .forEach(child -> {
                        child.setReturnedAt(Instant.now());
                        release(child.getAsset());
                    });
        }

        boolean allReturned = rental.getItems().stream().allMatch(i -> i.getReturnedAt() != null);
        if (allReturned) {
            rental.setStatus(RentalStatus.COMPLETED);
        }
        return toResponse(rentalRepository.save(rental), Instant.now());
    }

    /** Отмена активной аренды: все позиции освобождаются, начисления не делаются. */
    @Transactional
    public RentalResponse cancel(UUID rentalId) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("Отменить можно только активную аренду");
        }
        rental.getItems().stream()
                .filter(item -> item.getReturnedAt() == null)
                .forEach(item -> {
                    item.setReturnedAt(Instant.now());
                    release(item.getAsset());
                });
        rental.setStatus(RentalStatus.CANCELLED);
        return toResponse(rentalRepository.save(rental), Instant.now());
    }

    private Rental findRental(UUID id) {
        return rentalRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Аренда не найдена"));
    }

    /**
     * Актив свободен после возврата/отмены; смонтированные АКБ/зарядник возвращаются в «на технике».
     * NB: item.getAsset() — ленивый прокси базового Asset, при joined-наследовании
     * instanceof по нему всегда false — сначала разворачиваем прокси.
     */
    private static void release(Asset asset) {
        Asset real = Hibernate.unproxy(asset, Asset.class);
        boolean mounted = real instanceof BatteryAsset battery && battery.getBike() != null
                || real instanceof ChargerAsset charger && charger.getBike() != null;
        real.setStatus(mounted ? AssetStatus.MOUNTED : AssetStatus.AVAILABLE);
    }

    private RentalResponse toResponse(Rental rental, Instant now) {
        int paidAmount = financeTransactionRepository.incomeSumByRentalId(rental.getId());
        return RentalResponse.from(rental, now, paidAmount);
    }
}
