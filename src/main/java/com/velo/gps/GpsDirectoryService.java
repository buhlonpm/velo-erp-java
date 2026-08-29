package com.velo.gps;

import com.velo.asset.AssetEventService;
import com.velo.asset.AssetRepository;
import com.velo.asset.BikeAsset;
import com.velo.asset.WriteOffReason;
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
import com.velo.gps.dto.CreateGpsTrackerRequest;
import com.velo.gps.dto.CreateSimCardRequest;
import com.velo.gps.dto.GpsTrackerResponse;
import com.velo.gps.dto.SimCardResponse;
import com.velo.gps.dto.UpdateGpsTrackerRequest;
import com.velo.gps.dto.UpdateSimCardRequest;
import com.velo.gps.dto.WriteOffSimCardRequest;
import com.velo.gps.dto.WriteOffTrackerRequest;
import com.velo.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GpsDirectoryService {

    private static final String PURCHASE_CATEGORY = "Покупка оборудования";
    private static final Instant MIN_DATE = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant MAX_DATE = Instant.parse("2100-01-01T00:00:00Z");

    private final SimCardRepository simCardRepository;
    private final GpsTrackerRepository gpsTrackerRepository;
    private final AssetRepository assetRepository;
    private final FinanceAccountRepository financeAccountRepository;
    private final FinanceCategoryRepository financeCategoryRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final AssetEventService eventService;

    // ---------- SIM-карты ----------

    @Transactional(readOnly = true)
    public List<SimCardResponse> findSimCards(boolean onlyAvailable) {
        return simCardRepository.findAllByOrderByPhoneNumber().stream()
                // available = активная и не вставлена в трекер
                .filter(sim -> !onlyAvailable || sim.getStatus() == SimCardStatus.ACTIVE)
                .map(sim -> {
                    UUID trackerId = gpsTrackerRepository.findBySimCardId(sim.getId())
                            .map(GpsTracker::getId).orElse(null);
                    return SimCardResponse.from(sim, trackerId);
                })
                .filter(response -> !onlyAvailable || response.trackerId() == null)
                .toList();
    }

    @Transactional
    public SimCardResponse createSimCard(CreateSimCardRequest request, User author) {
        if (simCardRepository.existsByPhoneNumber(request.phoneNumber().trim())) {
            throw new ConflictException("SIM-карта с таким номером уже есть");
        }
        // «В комплекте с трекером»: цена обязана быть 0, расходная операция не создаётся,
        // дата покупки наследуется от трекера, симка сразу вставляется в него.
        GpsTracker bundledTracker = null;
        if (request.bundledTrackerId() != null) {
            if (request.purchasePrice() != null && request.purchasePrice() > 0) {
                throw new ConflictException("Комплектная SIM-карта не может иметь цену покупки");
            }
            bundledTracker = gpsTrackerRepository.findById(request.bundledTrackerId())
                    .orElseThrow(() -> new NotFoundException("GPS-трекер не найден"));
            if (bundledTracker.getStatus() != GpsTrackerStatus.ACTIVE) {
                throw new ConflictException("Трекер списан или продан — комплектную SIM-карту добавить нельзя");
            }
            if (bundledTracker.getSimCard() != null) {
                throw new ConflictException("В трекере уже установлена SIM-карта");
            }
        } else {
            // Отдельная покупка: дата, цена > 0 и счёт списания обязательны
            if (request.purchasedAt() == null) {
                throw new ConflictException("Укажите дату покупки SIM-карты");
            }
            if (request.purchasePrice() == null || request.purchasePrice() <= 0) {
                throw new ConflictException("Укажите цену покупки SIM-карты");
            }
            if (request.purchaseAccountId() == null) {
                throw new ConflictException("Укажите счёт списания за покупку SIM-карты");
            }
        }
        SimCard simCard = new SimCard();
        simCard.setPhoneNumber(request.phoneNumber().trim());
        simCard.setOperator(request.operator().trim());
        simCard.setNote(request.note() != null ? request.note() : "");
        if (bundledTracker != null) {
            simCard.setPurchasedAt(bundledTracker.getPurchasedAt());
            simCard.setPurchasePrice(0);
        } else {
            simCard.setPurchasedAt(request.purchasedAt());
            simCard.setPurchasePrice(request.purchasePrice());
        }
        SimCard saved = simCardRepository.save(simCard);

        if (bundledTracker != null) {
            saved.setBundledTracker(bundledTracker);
            bundledTracker.setSimCard(saved);
            gpsTrackerRepository.save(bundledTracker);
            return SimCardResponse.from(saved, bundledTracker.getId());
        }

        if (request.purchasePrice() != null && request.purchasePrice() > 0) {
            FinanceTransaction transaction = new FinanceTransaction();
            transaction.setAccount(financeAccountRepository.findById(request.purchaseAccountId())
                    .orElseThrow(() -> new NotFoundException("Счёт не найден")));
            transaction.setCategory(purchaseCategory());
            transaction.setKind(CategoryKind.EXPENSE);
            transaction.setAmount(request.purchasePrice());
            transaction.setDate(request.purchasedAt() != null ? request.purchasedAt() : Instant.now());
            transaction.setComment("Покупка SIM-карты: " + saved.getPhoneNumber());
            transaction.setSimCard(saved);
            transaction.setSystem(true);
            transaction.setCreatedBy(author);
            financeTransactionRepository.save(transaction);
        }
        return SimCardResponse.from(saved, null);
    }

    /** Списание SIM-карты: сломана/украдена/утеряна/прочее. Продать нельзя. */
    @Transactional
    public SimCardResponse writeOffSimCard(UUID id, WriteOffSimCardRequest request) {
        SimCard simCard = simCardRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SIM-карта не найдена"));
        if (simCard.getStatus() == SimCardStatus.WRITTEN_OFF) {
            throw new ConflictException("SIM-карта уже списана");
        }
        if (request.reason() == WriteOffReason.SOLD) {
            throw new ConflictException("SIM-карту продать нельзя");
        }
        if (gpsTrackerRepository.findBySimCardId(id).isPresent()) {
            throw new ConflictException("SIM-карта вставлена в трекер — сначала выньте её");
        }
        simCard.setStatus(SimCardStatus.WRITTEN_OFF);
        simCard.setWriteOffReason(request.reason());
        simCard.setWriteOffComment(request.comment());
        SimCard saved = simCardRepository.save(simCard);
        return SimCardResponse.from(saved, null);
    }

    /** Вернуть SIM-карту из списания. */
    @Transactional
    public SimCardResponse restoreSimCard(UUID id) {
        SimCard simCard = simCardRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SIM-карта не найдена"));
        if (simCard.getStatus() != SimCardStatus.WRITTEN_OFF) {
            throw new ConflictException("SIM-карта не списана");
        }
        simCard.setStatus(SimCardStatus.ACTIVE);
        simCard.setWriteOffReason(null);
        simCard.setWriteOffComment(null);
        SimCard saved = simCardRepository.save(simCard);
        UUID trackerId = gpsTrackerRepository.findBySimCardId(id).map(GpsTracker::getId).orElse(null);
        return SimCardResponse.from(saved, trackerId);
    }

    @Transactional
    public SimCardResponse updateSimCard(UUID id, UpdateSimCardRequest request) {
        SimCard simCard = simCardRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SIM-карта не найдена"));
        if (request.phoneNumber() != null && !request.phoneNumber().isBlank()) {
            String phone = request.phoneNumber().trim();
            if (!phone.equals(simCard.getPhoneNumber()) && simCardRepository.existsByPhoneNumber(phone)) {
                throw new ConflictException("SIM-карта с таким номером уже есть");
            }
            simCard.setPhoneNumber(phone);
        }
        if (request.operator() != null && !request.operator().isBlank()) {
            simCard.setOperator(request.operator().trim());
        }
        if (request.note() != null) {
            simCard.setNote(request.note());
        }
        // Дата/цена покупки — только у отдельно купленной симки; у комплектной они наследуются от трекера
        if (request.purchasedAt() != null || request.purchasePrice() != null) {
            if (simCard.getBundledTracker() != null) {
                throw new ConflictException(
                        "Комплектной SIM-карте дату и цену покупки менять нельзя — они наследуются от трекера");
            }
            if (request.purchasedAt() != null) {
                validatePurchaseDate(request.purchasedAt());
                simCard.setPurchasedAt(request.purchasedAt());
            }
            if (request.purchasePrice() != null) {
                if (request.purchasePrice() <= 0) {
                    throw new ConflictException("Цена покупки должна быть больше 0");
                }
                simCard.setPurchasePrice(request.purchasePrice());
            }
            // синхронизация системной операции покупки (у старых записей её может не быть — тогда просто поля)
            financeTransactionRepository
                    .findFirstBySimCard_IdAndCategory_Name(id, PURCHASE_CATEGORY)
                    .ifPresent(transaction -> {
                        transaction.setAmount(simCard.getPurchasePrice());
                        transaction.setDate(simCard.getPurchasedAt() != null
                                ? simCard.getPurchasedAt() : transaction.getDate());
                        financeTransactionRepository.save(transaction);
                    });
        }
        SimCard saved = simCardRepository.save(simCard);
        UUID trackerId = gpsTrackerRepository.findBySimCardId(id).map(GpsTracker::getId).orElse(null);
        return SimCardResponse.from(saved, trackerId);
    }

    @Transactional
    public void deleteSimCard(UUID id) {
        SimCard simCard = simCardRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("SIM-карта не найдена"));
        if (gpsTrackerRepository.findBySimCardId(id).isPresent()) {
            throw new ConflictException("SIM-карта вставлена в трекер — сначала удалите/обновите трекер");
        }
        simCardRepository.delete(simCard);
    }

    // ---------- GPS-трекеры ----------

    @Transactional(readOnly = true)
    public List<GpsTrackerResponse> findTrackers(boolean onlyAvailable) {
        return gpsTrackerRepository.findAllByOrderByModel().stream()
                .map(this::toResponse)
                .filter(response -> !onlyAvailable
                        || (response.status().equals("active") && response.installedBikeId() == null))
                .toList();
    }

    @Transactional
    public GpsTrackerResponse createTracker(CreateGpsTrackerRequest request, User author) {
        // Покупка обязательна: дата, цена > 0 и счёт списания
        if (request.purchasedAt() == null) {
            throw new ConflictException("Укажите дату покупки GPS-трекера");
        }
        if (request.purchasePrice() == null || request.purchasePrice() <= 0) {
            throw new ConflictException("Укажите цену покупки GPS-трекера");
        }
        if (request.purchaseAccountId() == null) {
            throw new ConflictException("Укажите счёт списания за покупку GPS-трекера");
        }
        GpsTracker tracker = new GpsTracker();
        tracker.setModel(request.model().trim());
        tracker.setImei(request.imei());
        if (request.simCardId() != null) {
            SimCard simCard = simCardRepository.findById(request.simCardId())
                    .orElseThrow(() -> new NotFoundException("SIM-карта не найдена"));
            if (gpsTrackerRepository.findBySimCardId(simCard.getId()).isPresent()) {
                throw new ConflictException("Эта SIM-карта уже вставлена в другой трекер");
            }
            tracker.setSimCard(simCard);
        }
        tracker.setPurchasedAt(request.purchasedAt());
        tracker.setPurchasePrice(request.purchasePrice());
        GpsTracker saved = gpsTrackerRepository.save(tracker);

        if (request.purchaseAccountId() != null && request.purchasePrice() != null && request.purchasePrice() > 0) {
            FinanceAccount account = financeAccountRepository.findById(request.purchaseAccountId())
                    .orElseThrow(() -> new NotFoundException("Счёт не найден"));
            FinanceTransaction transaction = new FinanceTransaction();
            transaction.setAccount(account);
            transaction.setCategory(purchaseCategory());
            transaction.setKind(CategoryKind.EXPENSE);
            transaction.setAmount(request.purchasePrice());
            transaction.setDate(request.purchasedAt() != null ? request.purchasedAt() : Instant.now());
            transaction.setComment("Покупка GPS-трекера: " + saved.getModel()
                    + (saved.getImei() != null ? " (IMEI " + saved.getImei() + ")" : ""));
            transaction.setGpsTracker(saved);
            transaction.setSystem(true);
            transaction.setCreatedBy(author);
            financeTransactionRepository.save(transaction);
        }
        return toResponse(saved);
    }

    /** Редактирование трекера: модель, IMEI, замена/извлечение симки, дата/цена покупки. */
    @Transactional
    public GpsTrackerResponse updateTracker(UUID id, UpdateGpsTrackerRequest request) {
        GpsTracker tracker = gpsTrackerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("GPS-трекер не найден"));
        if (request.model() != null && !request.model().isBlank()) {
            tracker.setModel(request.model().trim());
        }
        if (request.imei() != null) {
            tracker.setImei(request.imei());
        }
        if (request.purchasedAt() != null || request.purchasePrice() != null) {
            if (request.purchasedAt() != null) {
                validatePurchaseDate(request.purchasedAt());
                tracker.setPurchasedAt(request.purchasedAt());
            }
            if (request.purchasePrice() != null) {
                if (request.purchasePrice() <= 0) {
                    throw new ConflictException("Цена покупки должна быть больше 0");
                }
                tracker.setPurchasePrice(request.purchasePrice());
            }
            // синхронизация системной операции покупки (у старых записей её может не быть — тогда просто поля)
            financeTransactionRepository
                    .findFirstByGpsTracker_IdAndCategory_Name(id, PURCHASE_CATEGORY)
                    .ifPresent(transaction -> {
                        transaction.setAmount(tracker.getPurchasePrice());
                        transaction.setDate(tracker.getPurchasedAt() != null
                                ? tracker.getPurchasedAt() : transaction.getDate());
                        financeTransactionRepository.save(transaction);
                    });
        }
        if (Boolean.TRUE.equals(request.clearSimCard())) {
            tracker.setSimCard(null);
        } else if (request.simCardId() != null) {
            SimCard simCard = simCardRepository.findById(request.simCardId())
                    .orElseThrow(() -> new NotFoundException("SIM-карта не найдена"));
            gpsTrackerRepository.findBySimCardId(simCard.getId())
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw new ConflictException("Эта SIM-карта уже вставлена в другой трекер");
                    });
            tracker.setSimCard(simCard);
        }
        return toResponse(gpsTrackerRepository.save(tracker));
    }

    @Transactional
    public void deleteTracker(UUID id) {
        GpsTracker tracker = gpsTrackerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("GPS-трекер не найден"));
        if (assetRepository.findBikeByGpsTrackerId(id).isPresent()) {
            throw new ConflictException("Трекер установлен на велосипед — сначала снимите его");
        }
        gpsTrackerRepository.delete(tracker);
    }

    /**
     * Списание трекера. Денежной операции НЕ создаётся: деньги ушли при покупке;
     * потеря попадает во «вложено» велосипеда через written_off_from_bike.
     */
    @Transactional
    public GpsTrackerResponse writeOffTracker(UUID id, WriteOffTrackerRequest request) {
        GpsTracker tracker = gpsTrackerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("GPS-трекер не найден"));
        if (tracker.getStatus() == GpsTrackerStatus.WRITTEN_OFF) {
            throw new ConflictException("Трекер уже списан");
        }
        WriteOffReason reason = request != null && request.reason() != null
                ? request.reason() : WriteOffReason.BROKEN;
        Optional<BikeAsset> bike = assetRepository.findBikeByGpsTrackerId(id);
        bike.ifPresent(b -> {
            b.setGpsTracker(null);
            assetRepository.save(b);
        });

        if (reason == WriteOffReason.SOLD) {
            if (request == null || request.salePrice() == null || request.saleAccountId() == null) {
                throw new ConflictException("Для продажи укажите цену и счёт зачисления");
            }
            FinanceTransaction sale = new FinanceTransaction();
            sale.setAccount(financeAccountRepository.findById(request.saleAccountId())
                    .orElseThrow(() -> new NotFoundException("Счёт не найден")));
            sale.setCategory(saleCategory());
            sale.setKind(CategoryKind.INCOME);
            sale.setAmount(request.salePrice());
            sale.setDate(Instant.now());
            sale.setComment("Продажа GPS-трекера: " + tracker.getModel()
                    + (tracker.getImei() != null ? " (IMEI " + tracker.getImei() + ")" : ""));
            sale.setSystem(true);
            financeTransactionRepository.save(sale);
            tracker.setStatus(GpsTrackerStatus.SOLD);
        } else {
            tracker.setStatus(GpsTrackerStatus.WRITTEN_OFF);
        }
        tracker.setWriteOffReason(reason);
        tracker.setWriteOffComment(request != null ? request.comment() : null);
        // Каскад: вставленная симка списывается вместе с трекером — той же причиной и комментарием.
        // Сознательное исключение: отдельно симку с причиной sold списать нельзя (409 в writeOffSimCard),
        // но вместе с проданным трекером она уезжает с той же причиной. Денежной операции у симки нет:
        // при sold приход один — трекерский (создан выше). Связь разрываем, FK живёт на трекере.
        // Каскад идёт напрямую, не через writeOffSimCard: списание трекера доступно всем сотрудникам,
        // а у симки своё правило «нельзя списать вставленную» (409) — здесь оно не должно сработать.
        SimCard simCard = tracker.getSimCard();
        if (simCard != null) {
            simCard.setStatus(SimCardStatus.WRITTEN_OFF);
            simCard.setWriteOffReason(reason);
            simCard.setWriteOffComment(tracker.getWriteOffComment());
            tracker.setSimCard(null);
            simCardRepository.save(simCard);
        }
        GpsTracker saved = gpsTrackerRepository.save(tracker);
        bike.ifPresent(b -> eventService.record(b, com.velo.asset.AssetEventType.TRACKER_REMOVE,
                "Трекер " + tracker.getModel() + " выбыл: " + reason.getValue()));
        return toResponse(saved);
    }

    /** Вернуть трекер из списания (ошибочное списание). Проданный вернуть нельзя.
     *  Симку НЕ воскрешаем: при списании трекера она уехала каскадом, связь разорвана —
     *  при ошибочном списании симку возвращают отдельно через /sim-cards/{id}/restore. */
    @Transactional
    public GpsTrackerResponse restoreTracker(UUID id) {
        GpsTracker tracker = gpsTrackerRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("GPS-трекер не найден"));
        if (tracker.getStatus() == GpsTrackerStatus.SOLD) {
            throw new ConflictException("Проданный трекер вернуть нельзя — уже есть приходная операция");
        }
        if (tracker.getStatus() != GpsTrackerStatus.WRITTEN_OFF) {
            throw new ConflictException("Трекер не списан");
        }
        BikeAsset fromBike = tracker.getWrittenOffFromBike();
        tracker.setStatus(GpsTrackerStatus.ACTIVE);
        tracker.setWriteOffReason(null);
        tracker.setWriteOffComment(null);
        tracker.setWrittenOffFromBike(null);
        GpsTracker saved = gpsTrackerRepository.save(tracker);
        if (fromBike != null) {
            eventService.record(fromBike, com.velo.asset.AssetEventType.TRACKER_INSTALL,
                    "Трекер " + tracker.getModel() + " возвращён из списания (на склад)");
        }
        return toResponse(saved);
    }

    private GpsTrackerResponse toResponse(GpsTracker tracker) {
        UUID bikeId = null;
        String bikeName = null;
        Optional<BikeAsset> bike = assetRepository.findBikeByGpsTrackerId(tracker.getId());
        if (bike.isPresent()) {
            bikeId = bike.get().getId();
            bikeName = bike.get().getName() + " (" + bike.get().getInventoryNumber() + ")";
        }
        return GpsTrackerResponse.from(tracker, bikeId, bikeName);
    }

    private FinanceCategory purchaseCategory() {
        return ensureCategory(PURCHASE_CATEGORY, CategoryKind.EXPENSE);
    }

    /** Дата покупки в разумных пределах (2000–2100) — отсекает опечатки вроде года 40000. */
    private static void validatePurchaseDate(Instant purchasedAt) {
        if (purchasedAt.isBefore(MIN_DATE) || purchasedAt.isAfter(MAX_DATE)) {
            throw new BadRequestException("Некорректная дата покупки: " + purchasedAt);
        }
    }

    private FinanceCategory saleCategory() {
        return ensureCategory("Продажа оборудования", CategoryKind.INCOME);
    }

    private FinanceCategory ensureCategory(String name, CategoryKind kind) {
        return financeCategoryRepository.findByNameAndKind(name, kind)
                .orElseGet(() -> {
                    FinanceCategory created = new FinanceCategory();
                    created.setName(name);
                    created.setKind(kind);
                    return financeCategoryRepository.save(created);
                });
    }
}
