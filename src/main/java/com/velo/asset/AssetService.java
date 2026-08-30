package com.velo.asset;

import com.velo.asset.dto.AssetDetailResponse;
import com.velo.asset.dto.AssetEventResponse;
import com.velo.asset.dto.AssetResponse;
import com.velo.asset.dto.ChargeCycleLogEntry;
import com.velo.asset.dto.CreateAssetRequest;
import com.velo.asset.dto.MileageLogEntry;
import com.velo.asset.dto.RecordChargeCyclesRequest;
import com.velo.asset.dto.RecordMileageRequest;
import com.velo.asset.dto.UpdateAssetRequest;
import com.velo.asset.dto.UpdateMileageRequest;
import com.velo.asset.dto.WriteOffAssetRequest;
import com.velo.bikemodel.BikeModel;
import com.velo.bikemodel.BikeModelRepository;
import com.velo.bikemodel.dto.BikeModelResponse;
import com.velo.common.exception.BadRequestException;
import com.velo.common.exception.ConflictException;
import com.velo.common.exception.NotFoundException;
import com.velo.finance.CategoryKind;
import com.velo.finance.FinanceAccount;
import com.velo.finance.FinanceAccountRepository;
import com.velo.finance.FinanceCategory;
import com.velo.finance.FinanceCategoryRepository;
import com.velo.finance.FinanceTransaction;
import com.velo.finance.FinanceTransactionRepository;
import com.velo.finance.dto.TransactionResponse;
import com.velo.gps.GpsTracker;
import com.velo.gps.GpsTrackerRepository;
import com.velo.rental.RentalExtensionRepository;
import com.velo.rental.RentalRepository;
import com.velo.rental.dto.RentalResponse;
import com.velo.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssetService {

    private static final String PURCHASE_CATEGORY = "Покупка оборудования";
    private static final String SALE_CATEGORY = "Продажа оборудования";
    private static final Instant MIN_DATE = Instant.parse("2000-01-01T00:00:00Z");

    private final AssetRepository assetRepository;
    private final BikeModelRepository bikeModelRepository;
    private final AssetMileageLogRepository mileageLogRepository;
    private final AssetChargeCycleLogRepository chargeCycleLogRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final FinanceAccountRepository financeAccountRepository;
    private final FinanceCategoryRepository financeCategoryRepository;
    private final RentalRepository rentalRepository;
    private final RentalExtensionRepository rentalExtensionRepository;
    private final GpsTrackerRepository gpsTrackerRepository;
    private final AssetEventRepository eventRepository;
    private final AssetEventService eventService;

    @Transactional(readOnly = true)
    public List<AssetResponse> findAll(AssetType type, AssetStatus status) {
        List<Asset> assets;
        if (type != null && status != null) {
            assets = assetRepository.findAllByTypeAndStatus(classFor(type), status);
        } else if (type != null) {
            assets = assetRepository.findAllByType(classFor(type));
        } else if (status != null) {
            assets = assetRepository.findAllByStatusOrderByInventoryNumber(status);
        } else {
            assets = assetRepository.findAllByOrderByInventoryNumber();
        }
        return assets.stream().map(AssetResponse::from).toList();
    }

    @Transactional
    public AssetResponse create(CreateAssetRequest request, User author) {
        if (assetRepository.existsByInventoryNumber(request.inventoryNumber())) {
            throw new ConflictException("Актив с таким инвентарным номером уже существует");
        }
        // покупка обязательна: дата и цена > 0 + счёт списания;
        // исключение — актив «в комплекте с велосипедом»: цена 0, дата наследуется от велосипеда
        validatePurchaseDate(request.purchasedAt());
        if (request.purchasePrice() == null) {
            throw new ConflictException("Укажите цену покупки");
        }
        BikeAsset bundledBike = null;
        if (request.bundledBikeId() != null) {
            if (request.type() == AssetType.BIKE) {
                throw new ConflictException("В комплекте могут быть только АКБ и зарядники");
            }
            if (request.purchasePrice() > 0) {
                throw new ConflictException("Актив в комплекте с велосипедом — цена покупки должна быть 0");
            }
            bundledBike = findBike(request.bundledBikeId());
        } else {
            if (request.purchasePrice() <= 0) {
                throw new ConflictException("Цена покупки должна быть больше 0");
            }
            if (request.purchasedAt() == null) {
                throw new ConflictException("Укажите дату покупки");
            }
            if (request.purchaseAccountId() == null) {
                throw new ConflictException("Укажите счёт списания за покупку");
            }
        }
        Asset asset = switch (request.type()) {
            case BIKE -> buildBike(request);
            case BATTERY -> buildBattery(request);
            case CHARGER -> buildCharger(request);
        };
        asset.setInventoryNumber(request.inventoryNumber());
        if (request.name() != null && !request.name().isBlank()) {
            asset.setName(request.name());
        }
        if (request.description() != null) {
            asset.setDescription(request.description());
        }
        // Комплектный актив наследует дату покупки велосипеда — вручную не вводится
        asset.setPurchasedAt(bundledBike != null ? bundledBike.getPurchasedAt() : request.purchasedAt());
        asset.setPurchasePrice(request.purchasePrice());
        if (bundledBike != null) {
            if (asset instanceof BatteryAsset battery) {
                battery.setBundledBike(bundledBike);
            } else if (asset instanceof ChargerAsset charger) {
                charger.setBundledBike(bundledBike);
            }
        }
        Asset saved = assetRepository.save(asset);

        if (request.purchasePrice() > 0) {
            FinanceTransaction purchase = recordPurchaseExpense(saved, request.purchaseAccountId(), author);
            eventService.record(saved, AssetEventType.PURCHASE,
                    "Заведён в систему, покупка за " + request.purchasePrice() + " ₽",
                    request.purchasePrice(), purchase, author);
        } else {
            // цена 0 возможна только у комплектного актива
            eventService.record(saved, AssetEventType.PURCHASE,
                    "Заведён в систему, в комплекте с " + bundledBike.getName()
                            + " (" + bundledBike.getInventoryNumber() + ")",
                    0, null, author);
        }
        if (bundledBike != null) {
            // комплектный актив сразу монтируется на свой велосипед
            mountOnBike(saved, bundledBike, author);
        }
        return AssetResponse.from(saved);
    }

    @Transactional
    public AssetResponse update(UUID id, UpdateAssetRequest request) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));

        // Комплектная АКБ/зарядник: покупка наследуется от велосипеда, отдельно не редактируется
        boolean bundledComponent = (asset instanceof BatteryAsset battery && battery.getBundledBike() != null)
                || (asset instanceof ChargerAsset charger && charger.getBundledBike() != null);
        if (bundledComponent && (request.purchasedAt() != null || request.purchasePrice() != null)) {
            throw new ConflictException("Актив куплен в комплекте с велосипедом — "
                    + "дата и цена покупки наследуются от него и не редактируются");
        }

        if (request.inventoryNumber() != null && !request.inventoryNumber().equals(asset.getInventoryNumber())) {
            if (assetRepository.existsByInventoryNumber(request.inventoryNumber())) {
                throw new ConflictException("Актив с таким инвентарным номером уже существует");
            }
            asset.setInventoryNumber(request.inventoryNumber());
        }
        if (request.name() != null) {
            asset.setName(request.name());
        }
        if (request.description() != null) {
            asset.setDescription(request.description());
        }
        if (request.purchasedAt() != null) {
            validatePurchaseDate(request.purchasedAt());
            asset.setPurchasedAt(request.purchasedAt());
        }
        if (request.purchasePrice() != null) {
            asset.setPurchasePrice(request.purchasePrice());
        }
        // синхронизация операции и события покупки с карточкой
        if (request.purchasePrice() != null || request.purchasedAt() != null) {
            financeTransactionRepository
                    .findFirstByAsset_IdAndCategory_Name(id, PURCHASE_CATEGORY)
                    .ifPresent(transaction -> {
                        transaction.setAmount(asset.getPurchasePrice());
                        transaction.setDate(asset.getPurchasedAt() != null
                                ? asset.getPurchasedAt() : transaction.getDate());
                        financeTransactionRepository.save(transaction);
                        eventRepository.findFirstByAsset_IdAndType(id, AssetEventType.PURCHASE)
                                .ifPresent(event -> {
                                    event.setAmount(asset.getPurchasePrice());
                                    eventRepository.save(event);
                                });
                    });
        }

        if (asset instanceof BikeAsset bike) {
            if (request.modelId() != null) {
                bike.setModel(findModel(request.modelId()));
                bike.setName(BikeModelResponse.displayName(bike.getModel()));
            }
            if (request.vin() != null) {
                bike.setVin(request.vin());
            }
        } else if (asset instanceof BatteryAsset battery) {
            if (request.voltage() != null) {
                battery.setVoltage(request.voltage());
            }
            if (request.capacityAh() != null) {
                battery.setCapacityAh(request.capacityAh());
            }
            if (request.vin() != null) {
                battery.setVin(request.vin());
            }
        } else if (asset instanceof ChargerAsset charger) {
            if (request.powerW() != null) {
                charger.setPowerW(request.powerW());
            }
            if (request.connector() != null) {
                charger.setConnector(request.connector());
            }
        }
        return AssetResponse.from(assetRepository.save(asset));
    }

    /** Установить GPS-трекер на велосипед. Трекер можно поставить только на один велосипед. */
    @Transactional
    public AssetResponse installTracker(UUID assetId, UUID trackerId, User author) {
        BikeAsset bike = findBike(assetId);
        if (bike.getStatus() == AssetStatus.SOLD || bike.getStatus() == AssetStatus.DECOMMISSIONED) {
            throw new ConflictException("Велосипед выбыл — трекер установить нельзя");
        }
        if (bike.getGpsTracker() != null) {
            throw new ConflictException("На велосипеде уже установлен трекер — сначала снимите его");
        }
        GpsTracker tracker = gpsTrackerRepository.findById(trackerId)
                .orElseThrow(() -> new NotFoundException("GPS-трекер не найден"));
        if (tracker.getStatus() == com.velo.gps.GpsTrackerStatus.WRITTEN_OFF) {
            throw new ConflictException("Трекер списан — установить нельзя");
        }
        assetRepository.findBikeByGpsTrackerId(trackerId)
                .filter(other -> !other.getId().equals(assetId))
                .ifPresent(other -> {
                    throw new ConflictException("Этот трекер уже установлен на "
                            + other.getName() + " (" + other.getInventoryNumber() + ")");
                });
        bike.setGpsTracker(tracker);
        AssetResponse response = AssetResponse.from(assetRepository.save(bike));
        eventService.record(bike, AssetEventType.TRACKER_INSTALL,
                "Установлен трекер " + tracker.getModel(), null, null, author);
        return response;
    }

    /** Снять GPS-трекер с велосипеда. */
    @Transactional
    public AssetResponse removeTracker(UUID assetId, User author) {
        BikeAsset bike = findBike(assetId);
        String model = bike.getGpsTracker() != null ? bike.getGpsTracker().getModel() : null;
        bike.setGpsTracker(null);
        AssetResponse response = AssetResponse.from(assetRepository.save(bike));
        eventService.record(bike, AssetEventType.TRACKER_REMOVE,
                "Трекер снят" + (model != null ? ": " + model : ""), null, null, author);
        return response;
    }

    /** Смонтировать АКБ или зарядник на велосипед (на велосипеде — строго по одному каждого типа). */
    @Transactional
    public AssetResponse mountOnBike(UUID assetId, UUID bikeId, User author) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        if (!(asset instanceof BatteryAsset) && !(asset instanceof ChargerAsset)) {
            throw new ConflictException("Монтировать можно только АКБ или зарядник");
        }
        if (asset.getStatus() != AssetStatus.AVAILABLE) {
            throw new ConflictException("Актив недоступен (в аренде, списан или на обслуживании)");
        }
        BikeAsset alreadyOn = asset instanceof BatteryAsset battery ? battery.getBike()
                : ((ChargerAsset) asset).getBike();
        if (alreadyOn != null) {
            throw new ConflictException("Актив уже смонтирован на "
                    + alreadyOn.getName() + " (" + alreadyOn.getInventoryNumber() + ")");
        }
        BikeAsset bike = findBike(bikeId);
        if (bike.getStatus() != AssetStatus.AVAILABLE && bike.getStatus() != AssetStatus.MAINTENANCE) {
            throw new ConflictException("Велосипед недоступен для монтажа");
        }
        mountOnBike(asset, bike, author);
        return AssetResponse.from(assetRepository.save(asset));
    }

    /** Монтаж без проверок статусов (создание комплектного актива); проверка «по одному» — здесь. */
    private void mountOnBike(Asset asset, BikeAsset bike, User author) {
        boolean isBattery = asset instanceof BatteryAsset;
        if (isBattery) {
            assetRepository.findAllBatteriesByBikeId(bike.getId()).stream().findFirst().ifPresent(existing -> {
                throw new ConflictException("На велосипеде уже смонтирована АКБ "
                        + existing.getName() + " (" + existing.getInventoryNumber() + ") — сначала демонтируйте её");
            });
            ((BatteryAsset) asset).setBike(bike);
        } else if (asset instanceof ChargerAsset charger) {
            assetRepository.findAllChargersByBikeId(bike.getId()).stream().findFirst().ifPresent(existing -> {
                throw new ConflictException("На велосипеде уже смонтирован зарядник "
                        + existing.getName() + " (" + existing.getInventoryNumber() + ") — сначала демонтируйте его");
            });
            charger.setBike(bike);
        } else {
            throw new ConflictException("Монтировать можно только АКБ или зарядник");
        }
        asset.setStatus(AssetStatus.MOUNTED);
        assetRepository.save(asset);
        eventService.record(asset, AssetEventType.MOUNT,
                (isBattery ? "Смонтирована на " : "Смонтирован на ")
                        + bike.getName() + " (" + bike.getInventoryNumber() + ")", null, null, author);
        eventService.record(bike, AssetEventType.MOUNT,
                (isBattery ? "Смонтирована АКБ " : "Смонтирован зарядник ")
                        + asset.getName() + " (" + asset.getInventoryNumber() + ")", null, null, author);
    }

    /** Демонтировать АКБ или зарядник (возврат на склад). */
    @Transactional
    public AssetResponse unmountFromBike(UUID assetId, User author) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        boolean isBattery = asset instanceof BatteryAsset;
        if (!isBattery && !(asset instanceof ChargerAsset)) {
            throw new ConflictException("Демонтировать можно только АКБ или зарядник");
        }
        BikeAsset bike = isBattery ? ((BatteryAsset) asset).getBike() : ((ChargerAsset) asset).getBike();
        if (isBattery) {
            ((BatteryAsset) asset).setBike(null);
        } else {
            ((ChargerAsset) asset).setBike(null);
        }
        // на складе — снова доступен (если в аренде — статус вернёт возврат аренды)
        if (asset.getStatus() == AssetStatus.MOUNTED) {
            asset.setStatus(AssetStatus.AVAILABLE);
        }
        AssetResponse response = AssetResponse.from(assetRepository.save(asset));
        eventService.record(asset, AssetEventType.UNMOUNT,
                (isBattery ? "Демонтирована" : "Демонтирован") + (bike != null
                        ? " с " + bike.getName() + " (" + bike.getInventoryNumber() + ")" : ""),
                null, null, author);
        if (bike != null) {
            eventService.record(bike, AssetEventType.UNMOUNT,
                    (isBattery ? "Демонтирована АКБ " : "Демонтирован зарядник ")
                            + asset.getName() + " (" + asset.getInventoryNumber() + ")",
                    null, null, author);
        }
        return response;
    }

    /**
     * Выбытие актива: сломан / кража / утеря / продан / прочее.
     * Продажа создаёт приходную операцию; остальные причины денег не трогают.
     */
    @Transactional
    public AssetResponse writeOff(UUID id, WriteOffAssetRequest request, User author) {
        Asset asset = assetRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        if (asset.getStatus() == AssetStatus.RENTED) {
            throw new ConflictException("Актив в аренде — сначала завершите аренду");
        }
        if (asset.getStatus() == AssetStatus.SOLD || asset.getStatus() == AssetStatus.DECOMMISSIONED) {
            throw new ConflictException("Актив уже выбыл");
        }

        WriteOffReason reason = request.reason();
        FinanceTransaction saleTransaction = null;
        if (reason == WriteOffReason.SOLD) {
            if (request.salePrice() == null || request.saleAccountId() == null) {
                throw new ConflictException("Для продажи укажите цену и счёт зачисления");
            }
            saleTransaction = recordSaleIncome(asset, request.salePrice(), request.saleAccountId(), author);
        }

        // демонтаж с велосипеда
        if (asset instanceof BatteryAsset battery && battery.getBike() != null) {
            BikeAsset bike = battery.getBike();
            battery.setBike(null);
            eventService.record(bike, AssetEventType.UNMOUNT,
                    "АКБ " + battery.getName() + " (" + battery.getInventoryNumber()
                            + ") выбыла: " + reason.getValue(), null, null, author);
        }
        if (asset instanceof ChargerAsset charger && charger.getBike() != null) {
            BikeAsset bike = charger.getBike();
            charger.setBike(null);
            eventService.record(bike, AssetEventType.UNMOUNT,
                    "Зарядник " + charger.getName() + " (" + charger.getInventoryNumber()
                            + ") выбыл: " + reason.getValue(), null, null, author);
        }
        if (asset instanceof BikeAsset bike) {
            // АКБ и зарядник — обратно на склад
            assetRepository.findAllBatteriesByBikeId(id).forEach(battery -> {
                battery.setBike(null);
                if (battery.getStatus() == AssetStatus.MOUNTED) {
                    battery.setStatus(AssetStatus.AVAILABLE);
                }
                assetRepository.save(battery);
                eventService.record(battery, AssetEventType.UNMOUNT,
                        "Демонтирована: велосипед выбыл", null, null, author);
                eventService.record(bike, AssetEventType.UNMOUNT,
                        "АКБ " + battery.getInventoryNumber() + " демонтирована при выбытии",
                        null, null, author);
            });
            assetRepository.findAllChargersByBikeId(id).forEach(charger -> {
                charger.setBike(null);
                if (charger.getStatus() == AssetStatus.MOUNTED) {
                    charger.setStatus(AssetStatus.AVAILABLE);
                }
                assetRepository.save(charger);
                eventService.record(charger, AssetEventType.UNMOUNT,
                        "Демонтирован: велосипед выбыл", null, null, author);
                eventService.record(bike, AssetEventType.UNMOUNT,
                        "Зарядник " + charger.getInventoryNumber() + " демонтирован при выбытии",
                        null, null, author);
            });
            // трекер: при продаже уходит с велосипедом, иначе — на склад
            if (bike.getGpsTracker() != null && reason != WriteOffReason.SOLD) {
                String trackerModel = bike.getGpsTracker().getModel();
                bike.setGpsTracker(null);
                eventService.record(bike, AssetEventType.TRACKER_REMOVE,
                        "Трекер " + trackerModel + " снят при выбытии", null, null, author);
            }
        }

        asset.setStatus(reason == WriteOffReason.SOLD ? AssetStatus.SOLD : AssetStatus.DECOMMISSIONED);
        asset.setWriteOffReason(reason);
        asset.setWrittenOffAt(Instant.now());
        Asset saved = assetRepository.save(asset);

        StringBuilder eventComment = new StringBuilder("Выбытие: ").append(reason.getValue());
        if (request.comment() != null && !request.comment().isBlank()) {
            eventComment.append(". ").append(request.comment());
        }
        eventService.record(saved, AssetEventType.WRITE_OFF, eventComment.toString(),
                reason == WriteOffReason.SOLD ? request.salePrice() : null, saleTransaction, author);
        return AssetResponse.from(saved);
    }

    /** Карточка актива: паспорт, журнал пробега, привязанные операции, аренды, итоги. */
    @Transactional(readOnly = true)
    public AssetDetailResponse detail(UUID assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        Instant now = Instant.now();

        List<MileageLogEntry> mileage = mileageLogRepository
                .findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(assetId).stream()
                .map(MileageLogEntry::from)
                .toList();

        List<ChargeCycleLogEntry> chargeCycleLog = chargeCycleLogRepository
                .findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(assetId).stream()
                .map(ChargeCycleLogEntry::from)
                .toList();

        List<TransactionResponse> transactions = financeTransactionRepository
                .findAllByAssetIdOrderByDateDesc(assetId).stream()
                .map(TransactionResponse::from)
                .toList();

        List<RentalResponse> rentals = rentalRepository.findAllByAssetId(assetId).stream()
                .map(rental -> RentalResponse.from(rental, now,
                        financeTransactionRepository.paidSumByRentalId(rental.getId()),
                        financeTransactionRepository.refundedSumByRentalId(rental.getId()),
                        rentalExtensionRepository.findAllByRentalIdOrderByCreatedAtAsc(rental.getId())))
                .toList();

        int purchasePrice = asset.getPurchasePrice() != null ? asset.getPurchasePrice() : 0;
        int expenses = financeTransactionRepository.sumByAssetIdAndKindExcludingCategory(
                assetId, CategoryKind.EXPENSE, PURCHASE_CATEGORY);
        int income = financeTransactionRepository.sumByAssetIdAndKind(assetId, CategoryKind.INCOME);
        int rentalAccrued = rentalRepository.findAllByAssetId(assetId).stream()
                .flatMap(rental -> rental.getItems().stream())
                .filter(item -> item.getAsset().getId().equals(assetId))
                .mapToInt(item -> item.amount(now))
                .sum();

        // roll-up смонтированного оборудования (только для велосипеда)
        List<AssetResponse> mountedBatteries = List.of();
        List<AssetResponse> mountedChargers = List.of();
        int batteryTotal = 0;
        int chargerTotal = 0;
        if (asset instanceof BikeAsset bike) {
            var batteries = assetRepository.findAllBatteriesByBikeId(assetId);
            mountedBatteries = batteries.stream().map(AssetResponse::from).toList();
            for (BatteryAsset battery : batteries) {
                batteryTotal += (battery.getPurchasePrice() != null ? battery.getPurchasePrice() : 0)
                        + financeTransactionRepository.sumByAssetIdAndKindExcludingCategory(
                                battery.getId(), CategoryKind.EXPENSE, PURCHASE_CATEGORY);
            }
            var chargers = assetRepository.findAllChargersByBikeId(assetId);
            mountedChargers = chargers.stream().map(AssetResponse::from).toList();
            for (ChargerAsset charger : chargers) {
                chargerTotal += (charger.getPurchasePrice() != null ? charger.getPurchasePrice() : 0)
                        + financeTransactionRepository.sumByAssetIdAndKindExcludingCategory(
                                charger.getId(), CategoryKind.EXPENSE, PURCHASE_CATEGORY);
            }
        }

        List<AssetEventResponse> events = eventRepository.findAllByAssetIdOrderByDateDesc(assetId).stream()
                .map(AssetEventResponse::from)
                .toList();

        return new AssetDetailResponse(
                AssetResponse.from(asset), mountedBatteries, mountedChargers, mileage, chargeCycleLog,
                transactions, rentals, events,
                new AssetDetailResponse.Totals(purchasePrice, expenses, income, rentalAccrued,
                        batteryTotal, chargerTotal));
    }

    /** Записать пробег в журнал (велосипед или АКБ, вручную); текущий пробег = последняя по дате запись. */
    @Transactional
    public MileageLogEntry recordMileage(UUID assetId, RecordMileageRequest request, User author) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        if (!(asset instanceof BikeAsset) && !(asset instanceof BatteryAsset)) {
            throw new ConflictException("Пробег есть только у велосипедов и АКБ");
        }

        // пробег не может уменьшаться: новая запись не меньше актуального значения
        Integer current = asset instanceof BikeAsset bike
                ? bike.getMileageKm()
                : ((BatteryAsset) asset).getMileageKm();
        if (current != null && request.mileageKm() < current) {
            throw new ConflictException(
                    "Новый пробег не может быть меньше текущего (" + current + " км)");
        }

        AssetMileageLog log = new AssetMileageLog();
        log.setAssetId(assetId);
        log.setMileageKm(request.mileageKm());
        log.setRecordedAt(request.recordedAt() != null ? request.recordedAt() : Instant.now());
        AssetMileageLog saved = mileageLogRepository.save(log);

        recalcMileageCache(asset);
        eventService.record(asset, AssetEventType.MILEAGE,
                "Пробег: " + request.mileageKm() + " км", null, null, author);
        return MileageLogEntry.from(saved);
    }

    /**
     * Правка записи пробега (значение и/или дата). Журнал монотонный: после правки запись
     * должна остаться между соседями по дате (prev ≤ значение ≤ next), иначе 409.
     */
    @Transactional
    public MileageLogEntry updateMileageEntry(UUID assetId, UUID logId,
                                              UpdateMileageRequest request, User author) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        AssetMileageLog log = mileageLogRepository.findById(logId)
                .filter(entry -> entry.getAssetId().equals(assetId))
                .orElseThrow(() -> new NotFoundException("Запись пробега не найдена"));

        int oldValue = log.getMileageKm();
        Instant oldDate = log.getRecordedAt();
        int newValue = request.mileageKm() != null ? request.mileageKm() : oldValue;
        Instant newDate = request.recordedAt() != null ? request.recordedAt() : oldDate;
        if (newValue == oldValue && newDate.equals(oldDate)) {
            return MileageLogEntry.from(log);
        }
        validateMileagePosition(assetId, log, newValue, newDate);

        log.setMileageKm(newValue);
        log.setRecordedAt(newDate);
        AssetMileageLog saved = mileageLogRepository.save(log);
        recalcMileageCache(asset);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault());
        List<String> parts = new ArrayList<>();
        if (newValue != oldValue) {
            parts.add("пробег: " + oldValue + " км → " + newValue + " км");
        }
        if (!newDate.equals(oldDate)) {
            parts.add("дата: " + formatter.format(oldDate) + " → " + formatter.format(newDate));
        }
        eventService.record(asset, AssetEventType.MILEAGE,
                "Пробег изменён: " + String.join("; ", parts), null, null, author);
        return MileageLogEntry.from(saved);
    }

    /** Удаление записи пробега; текущее значение пересчитывается от оставшихся. */
    @Transactional
    public void deleteMileageEntry(UUID assetId, UUID logId, User author) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        AssetMileageLog log = mileageLogRepository.findById(logId)
                .filter(entry -> entry.getAssetId().equals(assetId))
                .orElseThrow(() -> new NotFoundException("Запись пробега не найдена"));
        mileageLogRepository.delete(log);
        recalcMileageCache(asset);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault());
        eventService.record(asset, AssetEventType.MILEAGE,
                "Пробег удалён: " + log.getMileageKm() + " км; дата: " + formatter.format(log.getRecordedAt()),
                null, null, author);
    }

    /**
     * Монотонность журнала: в позиции новой даты значение должно быть не меньше предыдущей
     * записи и не больше следующей (порядок recorded_at + тай-брейк created_at).
     */
    private void validateMileagePosition(UUID assetId, AssetMileageLog log, int newValue, Instant newDate) {
        List<AssetMileageLog> others = mileageLogRepository
                .findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(assetId).stream()
                .filter(entry -> !entry.getId().equals(log.getId()))
                .toList();
        // список свежие-первыми: ищем точку вставки новой позиции
        int insertAt = 0;
        while (insertAt < others.size() && isNewer(others.get(insertAt), newDate, log.getCreatedAt())) {
            insertAt++;
        }
        AssetMileageLog newer = insertAt > 0 ? others.get(insertAt - 1) : null;
        AssetMileageLog older = insertAt < others.size() ? others.get(insertAt) : null;
        if (older != null && newValue < older.getMileageKm()) {
            throw new ConflictException("Пробег " + newValue + " км меньше более ранней записи журнала ("
                    + older.getMileageKm() + " км)");
        }
        if (newer != null && newValue > newer.getMileageKm()) {
            throw new ConflictException("Пробег " + newValue + " км больше более поздней записи журнала ("
                    + newer.getMileageKm() + " км)");
        }
    }

    /** entry строго новее позиции (recordedAt, createdAt)? */
    private static boolean isNewer(AssetMileageLog entry, Instant recordedAt, Instant createdAt) {
        int byDate = entry.getRecordedAt().compareTo(recordedAt);
        return byDate > 0 || (byDate == 0 && entry.getCreatedAt().compareTo(createdAt) > 0);
    }

    /** Кэш текущего пробега на активе = последняя по дате запись; пустой журнал → null. */
    private void recalcMileageCache(Asset asset) {
        Integer current = mileageLogRepository
                .findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(asset.getId()).stream()
                .findFirst()
                .map(AssetMileageLog::getMileageKm)
                .orElse(null);
        if (asset instanceof BikeAsset bike) {
            bike.setMileageKm(current);
        } else if (asset instanceof BatteryAsset battery) {
            battery.setMileageKm(current);
        }
        assetRepository.save(asset);
    }

    @Transactional(readOnly = true)
    public List<MileageLogEntry> mileageLog(UUID assetId) {
        if (!assetRepository.existsById(assetId)) {
            throw new NotFoundException("Актив не найден");
        }
        return mileageLogRepository.findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(assetId).stream()
                .map(MileageLogEntry::from)
                .toList();
    }

    /** Записать циклы перезарядки в журнал АКБ; текущее значение = последняя по дате запись. */
    @Transactional
    public ChargeCycleLogEntry recordChargeCycles(UUID assetId, RecordChargeCyclesRequest request, User author) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        if (!(asset instanceof BatteryAsset battery)) {
            throw new ConflictException("Циклы перезарядки есть только у АКБ");
        }

        // циклы не могут уменьшаться: новая запись не меньше актуального значения
        Integer current = battery.getChargeCycles();
        if (current != null && request.cycles() < current) {
            throw new ConflictException(
                    "Новое значение циклов не может быть меньше текущего (" + current + ")");
        }

        AssetChargeCycleLog log = new AssetChargeCycleLog();
        log.setAssetId(assetId);
        log.setCycles(request.cycles());
        log.setRecordedAt(request.recordedAt() != null ? request.recordedAt() : Instant.now());
        AssetChargeCycleLog saved = chargeCycleLogRepository.save(log);

        // кэш текущего значения — последняя по дате запись
        chargeCycleLogRepository.findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(assetId).stream()
                .findFirst()
                .ifPresent(latest -> battery.setChargeCycles(latest.getCycles()));
        assetRepository.save(battery);
        eventService.record(asset, AssetEventType.CHARGE_CYCLES,
                "Циклы перезарядки: " + request.cycles(), null, null, author);
        return ChargeCycleLogEntry.from(saved);
    }

    @Transactional(readOnly = true)
    public List<ChargeCycleLogEntry> chargeCycleLog(UUID assetId) {
        if (!assetRepository.existsById(assetId)) {
            throw new NotFoundException("Актив не найден");
        }
        return chargeCycleLogRepository.findAllByAssetIdOrderByRecordedAtDescCreatedAtDesc(assetId).stream()
                .map(ChargeCycleLogEntry::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssetEventResponse> events(UUID assetId) {
        if (!assetRepository.existsById(assetId)) {
            throw new NotFoundException("Актив не найден");
        }
        return eventRepository.findAllByAssetIdOrderByDateDesc(assetId).stream()
                .map(AssetEventResponse::from)
                .toList();
    }

    private FinanceTransaction recordPurchaseExpense(Asset asset, UUID accountId, User author) {
        FinanceAccount account = financeAccountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Счёт не найден"));
        FinanceCategory category = financeCategoryRepository
                .findByNameAndKind(PURCHASE_CATEGORY, CategoryKind.EXPENSE)
                .orElseGet(() -> {
                    FinanceCategory created = new FinanceCategory();
                    created.setName(PURCHASE_CATEGORY);
                    created.setKind(CategoryKind.EXPENSE);
                    return financeCategoryRepository.save(created);
                });

        FinanceTransaction transaction = new FinanceTransaction();
        transaction.setAccount(account);
        transaction.setCategory(category);
        transaction.setKind(CategoryKind.EXPENSE);
        transaction.setAmount(asset.getPurchasePrice());
        transaction.setDate(asset.getPurchasedAt() != null ? asset.getPurchasedAt() : Instant.now());
        transaction.setComment("Покупка: " + asset.getName() + " (" + asset.getInventoryNumber() + ")");
        transaction.setAsset(asset);
        transaction.setSystem(true);
        transaction.setCreatedBy(author);
        return financeTransactionRepository.save(transaction);
    }

    private FinanceTransaction recordSaleIncome(Asset asset, int price, UUID accountId, User author) {
        FinanceAccount account = financeAccountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Счёт не найден"));
        FinanceCategory category = financeCategoryRepository
                .findByNameAndKind(SALE_CATEGORY, CategoryKind.INCOME)
                .orElseGet(() -> {
                    FinanceCategory created = new FinanceCategory();
                    created.setName(SALE_CATEGORY);
                    created.setKind(CategoryKind.INCOME);
                    return financeCategoryRepository.save(created);
                });

        FinanceTransaction transaction = new FinanceTransaction();
        transaction.setAccount(account);
        transaction.setCategory(category);
        transaction.setKind(CategoryKind.INCOME);
        transaction.setAmount(price);
        transaction.setDate(Instant.now());
        transaction.setComment("Продажа: " + asset.getName() + " (" + asset.getInventoryNumber() + ")");
        transaction.setAsset(asset);
        transaction.setSystem(true);
        transaction.setCreatedBy(author);
        return financeTransactionRepository.save(transaction);
    }

    private BikeAsset findBike(UUID assetId) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Актив не найден"));
        if (!(asset instanceof BikeAsset bike)) {
            throw new ConflictException("Операция доступна только для велосипеда");
        }
        return bike;
    }

    /** Дата покупки: не раньше 2000 года (опечатки) и не в будущем. */
    private static void validatePurchaseDate(Instant purchasedAt) {
        if (purchasedAt == null) {
            return;
        }
        if (purchasedAt.isBefore(MIN_DATE)) {
            throw new BadRequestException("Некорректная дата покупки: " + purchasedAt);
        }
        if (purchasedAt.isAfter(Instant.now())) {
            throw new BadRequestException("Дата покупки не может быть в будущем");
        }
    }

    private BikeAsset buildBike(CreateAssetRequest request) {
        BikeAsset bike = new BikeAsset();
        BikeModel model = request.modelId() != null ? findModel(request.modelId()) : null;
        bike.setModel(model);
        bike.setVin(request.vin());
        bike.setMileageKm(request.mileageKm() != null ? request.mileageKm() : 0);
        if (request.gpsTrackerId() != null) {
            GpsTracker tracker = gpsTrackerRepository.findById(request.gpsTrackerId())
                    .orElseThrow(() -> new NotFoundException("GPS-трекер не найден"));
            bike.setGpsTracker(tracker);
        }
        bike.setName(model != null ? BikeModelResponse.displayName(model) : "Велосипед");
        return bike;
    }

    private BatteryAsset buildBattery(CreateAssetRequest request) {
        BatteryAsset battery = new BatteryAsset();
        battery.setVoltage(request.voltage());
        battery.setVin(request.vin());
        battery.setCapacityAh(request.capacityAh());
        battery.setName(specsName("АКБ", request.voltage(), "V", request.capacityAh(), "Ah"));
        return battery;
    }

    private ChargerAsset buildCharger(CreateAssetRequest request) {
        ChargerAsset charger = new ChargerAsset();
        charger.setPowerW(request.powerW());
        charger.setConnector(request.connector());
        charger.setName("Зарядное устройство");
        return charger;
    }

    private BikeModel findModel(UUID modelId) {
        return bikeModelRepository.findById(modelId)
                .orElseThrow(() -> new NotFoundException("Модель не найдена в справочнике"));
    }

    private static String specsName(String prefix, Integer v1, String u1, Integer v2, String u2) {
        StringBuilder sb = new StringBuilder(prefix);
        if (v1 != null) {
            sb.append(' ').append(v1).append(u1);
        }
        if (v2 != null) {
            sb.append(' ').append(v2).append(u2);
        }
        return sb.toString();
    }

    static Class<? extends Asset> classFor(AssetType type) {
        return switch (type) {
            case BIKE -> BikeAsset.class;
            case BATTERY -> BatteryAsset.class;
            case CHARGER -> ChargerAsset.class;
        };
    }
}
