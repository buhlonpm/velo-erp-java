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
import com.velo.finance.CategoryKind;
import com.velo.finance.FinanceAccount;
import com.velo.finance.FinanceAccountRepository;
import com.velo.finance.FinanceCategory;
import com.velo.finance.FinanceCategoryRepository;
import com.velo.finance.FinanceTransaction;
import com.velo.finance.FinanceTransactionRepository;
import com.velo.rental.dto.CompleteRentalRequest;
import com.velo.rental.dto.CreateRentalRequest;
import com.velo.rental.dto.ExtendRentalRequest;
import com.velo.rental.dto.ExtensionRequest;
import com.velo.rental.dto.IssueRentalRequest;
import com.velo.rental.dto.PaymentRequest;
import com.velo.rental.dto.RentalEventResponse;
import com.velo.rental.dto.RentalResponse;
import com.velo.rental.dto.ReturnItemRequest;
import com.velo.tariff.TariffUnit;
import com.velo.user.User;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RentalService {

    private final RentalRepository rentalRepository;
    private final CustomerRepository customerRepository;
    private final AssetRepository assetRepository;
    private final FinanceTransactionRepository financeTransactionRepository;
    private final FinanceAccountRepository financeAccountRepository;
    private final FinanceCategoryRepository financeCategoryRepository;
    private final RentalEventRepository rentalEventRepository;
    private final RentalExtensionRepository rentalExtensionRepository;

    /** Системные статьи для операций аренды (создаются на лету, как «Покупка оборудования»). */
    private static final String RENTAL_PAYMENT_CATEGORY = "Оплата аренды";
    private static final String RENTAL_REFUND_CATEGORY = "Возврат по аренде";

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
    public RentalResponse create(CreateRentalRequest request, User author) {
        RentalKind kind = request.kind() != null ? request.kind() : RentalKind.RENT;
        Instant startAt = request.startAt() != null ? request.startAt() : Instant.now();
        // rent: конец периода фиксированный, считает сервер из срока; rent_to_own — без срока
        Instant plannedEndAt = null;
        if (kind == RentalKind.RENT) {
            if (request.duration() == null || request.durationUnit() == null) {
                throw new ConflictException("Укажите срок аренды (количество и единицу: час/день/неделя/месяц)");
            }
            plannedEndAt = startAt.plusSeconds(request.duration() * request.durationUnit().getSeconds());
        }
        if (kind == RentalKind.RENT_TO_OWN && request.buyoutPrice() == null) {
            throw new ConflictException("Для выкупа нужна цена выкупа");
        }

        Customer customer = customerRepository.findById(request.customerId())
                .orElseThrow(() -> new NotFoundException("Клиент не найден"));

        Rental rental = new Rental();
        rental.setCustomer(customer);
        rental.setKind(kind);
        rental.setStartAt(startAt);
        rental.setPlannedEndAt(plannedEndAt);
        rental.setBuyoutPrice(request.buyoutPrice());
        rental.setComment(request.comment() != null ? request.comment() : "");

        for (CreateRentalRequest.Item itemRequest : request.items()) {
            Asset asset = assetRepository.findById(itemRequest.assetId())
                    .orElseThrow(() -> new NotFoundException("Актив не найден: " + itemRequest.assetId()));
            if (asset.getStatus() != AssetStatus.AVAILABLE) {
                throw new ConflictException("Актив недоступен: " + asset.getName()
                        + " (" + asset.getInventoryNumber() + ")");
            }
            // rent: единица тарифа позиции = единице срока аренды (клиент её не задаёт);
            // rent_to_own: срока нет — единицу шлём в позиции, обязательна
            TariffUnit itemUnit = kind == RentalKind.RENT ? request.durationUnit() : itemRequest.tariffUnit();
            if (itemUnit == null) {
                throw new ConflictException("Укажите единицу тарифа (час/день/неделя/месяц) для позиции: "
                        + asset.getName() + " (" + asset.getInventoryNumber() + ")");
            }
            RentalItem item = new RentalItem();
            item.setRental(rental);
            item.setAsset(asset);
            item.setTariffUnit(itemUnit);
            item.setRate(itemRequest.rate() != null ? itemRequest.rate() : 0);
            rental.getItems().add(item);
            // черновик резервирует актив до выдачи
            asset.setStatus(AssetStatus.RESERVED);
        }

        // авто-комплект: АКБ и зарядник, смонтированные на велосипедах из позиций,
        // едут дочерними позициями с тарифом 0 — отдельных тарифов у комплекта нет
        for (RentalItem parent : List.copyOf(rental.getItems())) {
            if (!(parent.getAsset() instanceof BikeAsset bike)) {
                continue;
            }
            List<Asset> kit = new ArrayList<>(assetRepository.findAllBatteriesByBikeId(bike.getId()));
            kit.addAll(assetRepository.findAllChargersByBikeId(bike.getId()));
            for (Asset kitAsset : kit) {
                if (kitAsset.getStatus() != AssetStatus.AVAILABLE
                        && kitAsset.getStatus() != AssetStatus.MOUNTED) {
                    throw new ConflictException("Комплект велосипеда недоступен: "
                            + kitAsset.getName() + " (" + kitAsset.getInventoryNumber() + ")");
                }
                RentalItem child = new RentalItem();
                child.setRental(rental);
                child.setAsset(kitAsset);
                // комплект едет по тарифу 0, единица — как у родительской позиции (велосипеда)
                child.setTariffUnit(parent.getTariffUnit());
                child.setRate(0);
                child.setParentItem(parent);
                rental.getItems().add(child);
                kitAsset.setStatus(AssetStatus.RESERVED);
            }
        }

        Rental saved = rentalRepository.save(rental);
        recordEvent(saved, RentalEventType.CREATED,
                kind == RentalKind.RENT
                        ? "Аренда создана (черновик), срок " + request.duration() + " × "
                                + durationUnitLabel(request.durationUnit())
                        : "Аренда под выкуп создана (черновик)",
                null, null, author);
        return toResponse(saved, Instant.now());
    }

    /**
     * Приём оплаты по аренде (черновик или активная; платежей может быть несколько).
     * Дату платежа можно указать (по умолчанию — сейчас), счёт обязателен.
     */
    @Transactional
    public RentalResponse addPayment(UUID rentalId, PaymentRequest request, User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.DRAFT && rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("По завершённой или отменённой аренде платежи не принимаются");
        }
        Instant date = request.date() != null ? request.date() : Instant.now();
        FinanceTransaction payment = recordRentalTransaction(rental, CategoryKind.INCOME,
                RENTAL_PAYMENT_CATEGORY, request.amount(), request.accountId(), "Оплата аренды", author, date);
        recordEvent(rental, RentalEventType.PAYMENT, "Принята оплата; дата: " + fmt(payment.getDate()),
                request.amount(), payment, author, payment.getDate());
        return toResponse(rental, Instant.now());
    }

    /**
     * Выдача черновика: статус → active, активы → «в аренде», период считается от даты выдачи
     * (startAt сдвигается на неё, plannedEndAt сохраняет длительность периода).
     * Выдать можно и без полной оплаты — доплата принимается отдельными платежами.
     */
    @Transactional
    public RentalResponse issue(UUID rentalId, IssueRentalRequest request, User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.DRAFT) {
            throw new ConflictException("Выдать можно только аренду-черновик");
        }
        Instant issuedAt = request != null && request.date() != null ? request.date() : Instant.now();
        if (rental.getPlannedEndAt() != null) {
            long periodSeconds = rental.getPlannedEndAt().getEpochSecond() - rental.getStartAt().getEpochSecond();
            rental.setPlannedEndAt(issuedAt.plusSeconds(periodSeconds));
        }
        rental.setStartAt(issuedAt);
        rental.setStatus(RentalStatus.ACTIVE);
        rental.getItems().forEach(item -> item.getAsset().setStatus(AssetStatus.RENTED));
        Rental saved = rentalRepository.save(rental);
        recordEvent(saved, RentalEventType.ISSUED, "Аренда выдана; дата: " + fmt(issuedAt),
                null, null, author, issuedAt);
        return toResponse(saved, Instant.now());
    }

    /**
     * Обычное завершение активной аренды (основной путь): все невозвращённые позиции
     * возвращаются (комплект — на технику), статус → «завершена». Денежных операций нет.
     * Дата приёма — опционально (по умолчанию сейчас); строго в календарный день окончания аренды.
     * Завершить можно только при полной оплате (оплачено >= начислено на дату приёма, иначе 409).
     */
    @Transactional
    public RentalResponse complete(UUID rentalId, CompleteRentalRequest request, User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("Завершить можно только выданную аренду");
        }
        Instant returnedAt = request != null && request.date() != null ? request.date() : Instant.now();
        assertSameCalendarDay(rental, returnedAt);
        assertFullyPaid(rental, returnedAt);
        rental.getItems().stream()
                .filter(item -> item.getReturnedAt() == null)
                .forEach(item -> {
                    item.setReturnedAt(returnedAt);
                    release(item.getAsset());
                });
        rental.setStatus(RentalStatus.COMPLETED);
        // конец периода = фактическая дата завершения
        rental.setPlannedEndAt(returnedAt);
        Rental saved = rentalRepository.save(rental);
        recordEvent(saved, RentalEventType.COMPLETED,
                "Аренда завершена; дата завершения: " + fmt(returnedAt), null, null, author, returnedAt);
        return toResponse(saved, returnedAt);
    }

    /**
     * Досрочный возврат (редкий путь — клиент вернул раньше и просит деньги):
     * статус ВСЕГДА «завершена досрочно», все позиции возвращаются. Только при полной оплате (409).
     * Дата приёма — строго в календарный день ДО дня окончания аренды (в день окончания или позже
     * — это обычное завершение, 409) и не раньше дня начала. Сумма возврата не больше переплаты
     * (оплачено − начислено за фактический срок, 409): вернуть можно только разницу.
     */
    @Transactional
    public RentalResponse earlyReturn(UUID rentalId, ReturnItemRequest request, User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("Завершить можно только выданную аренду");
        }
        if (request != null && request.refundAmount() != null && request.refundAmount() > 0
                && request.refundAccountId() == null) {
            throw new ConflictException("Укажите счёт, с которого вернуть деньги");
        }
        Instant returnedAt = request != null && request.date() != null ? request.date() : Instant.now();
        assertEarlyReturnDay(rental, returnedAt);
        assertFullyPaid(rental, returnedAt);
        int overpaid = financeTransactionRepository.paidSumByRentalId(rental.getId())
                - RentalAmounts.accruedActual(rental, returnedAt);
        if (request != null && request.refundAmount() != null && request.refundAmount() > overpaid) {
            throw new ConflictException("Сумма возврата не может быть больше переплаты ("
                    + overpaid + " ₽)");
        }
        rental.getItems().stream()
                .filter(item -> item.getReturnedAt() == null)
                .forEach(item -> {
                    item.setReturnedAt(returnedAt);
                    release(item.getAsset());
                });
        rental.setStatus(RentalStatus.COMPLETED_EARLY);
        // конец периода = фактическая дата завершения
        rental.setPlannedEndAt(returnedAt);
        Rental saved = rentalRepository.save(rental);

        if (request != null && request.refundAmount() != null && request.refundAmount() > 0) {
            FinanceTransaction refund = recordRentalTransaction(saved, CategoryKind.EXPENSE,
                    RENTAL_REFUND_CATEGORY, request.refundAmount(), request.refundAccountId(),
                    "Возврат денег клиенту по аренде", author, returnedAt);
            recordEvent(saved, RentalEventType.REFUND,
                    "Возврат денег клиенту; дата: " + fmt(returnedAt),
                    request.refundAmount(), refund, author, returnedAt);
        }
        recordEvent(saved, RentalEventType.COMPLETED,
                "Аренда завершена досрочно; дата завершения: " + fmt(returnedAt),
                null, null, author, returnedAt);
        return toResponse(saved, returnedAt);
    }

    /** Возврат одной позиции (опционально — с возвратом денег). Все позиции возвращены → аренда завершена. */
    @Transactional
    public RentalResponse returnItem(UUID rentalId, UUID itemId, ReturnItemRequest request, User author) {
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
        if (request != null && request.refundAmount() != null && request.refundAmount() > 0
                && request.refundAccountId() == null) {
            throw new ConflictException("Укажите счёт, с которого вернуть деньги");
        }

        Instant now = request != null && request.date() != null ? request.date() : Instant.now();
        item.setReturnedAt(now);
        release(item.getAsset());
        recordEvent(rental, RentalEventType.ITEM_RETURN,
                "Возврат: " + item.getAsset().getName() + " (" + item.getAsset().getInventoryNumber()
                        + "); дата: " + fmt(now),
                null, null, author, now);

        // вернули родителя — возвращаются и дочерние позиции комплекта
        if (item.getParentItem() == null) {
            rental.getItems().stream()
                    .filter(i -> i.getParentItem() != null
                            && i.getParentItem().getId().equals(itemId)
                            && i.getReturnedAt() == null)
                    .forEach(child -> {
                        child.setReturnedAt(now);
                        release(child.getAsset());
                        recordEvent(rental, RentalEventType.ITEM_RETURN,
                                "Возврат (комплект): " + child.getAsset().getName()
                                        + " (" + child.getAsset().getInventoryNumber() + "); дата: " + fmt(now),
                                null, null, author, now);
                    });
        }

        // возврат денег клиенту: расходная операция со счёта + событие в ленту
        if (request != null && request.refundAmount() != null && request.refundAmount() > 0) {
            FinanceTransaction refund = recordRentalTransaction(rental, CategoryKind.EXPENSE,
                    RENTAL_REFUND_CATEGORY, request.refundAmount(), request.refundAccountId(),
                    "Возврат денег клиенту по аренде", author);
            recordEvent(rental, RentalEventType.REFUND,
                    "Возврат денег клиенту; дата: " + fmt(now), request.refundAmount(), refund, author, now);
        }

        boolean allReturned = rental.getItems().stream().allMatch(i -> i.getReturnedAt() != null);
        if (allReturned) {
            rental.setStatus(RentalStatus.COMPLETED);
        }
        return toResponse(rentalRepository.save(rental), Instant.now());
    }

    /**
     * Отмена черновика: позиции освобождаются из резерва, принятые по черновику платежи
     * удаляются вместе с их событиями в ленте (деньги клиенту возвращают вне системы).
     * Выданную аренду отменить нельзя — только возврат.
     */
    @Transactional
    public RentalResponse cancel(UUID rentalId, User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.DRAFT) {
            throw new ConflictException("Отменить можно только черновик — выданную аренду оформляйте возвратом");
        }
        // на черновике могут быть только приходные платежи; события payment ссылаются
        // на них по FK — удаляем события целиком, затем сами операции
        rentalEventRepository.deleteByRentalIdAndType(rentalId, RentalEventType.PAYMENT);
        financeTransactionRepository.findAllByRentalIdOrderByDateDesc(rentalId)
                .forEach(financeTransactionRepository::delete);
        rental.getItems().stream()
                .filter(item -> item.getReturnedAt() == null)
                .forEach(item -> {
                    item.setReturnedAt(Instant.now());
                    release(item.getAsset());
                });
        rental.setStatus(RentalStatus.CANCELLED);
        Rental saved = rentalRepository.save(rental);
        recordEvent(saved, RentalEventType.CANCELLED, "Аренда отменена", null, null, author);
        return toResponse(saved, Instant.now());
    }

    /**
     * Удаление аренды без следа (только ADMIN — контроллер; только финальные статусы:
     * cancelled/completed/completed_early). Каскадно стираются события ленты, продления и ВСЕ
     * финансовые операции по аренде (оплаты/возвраты; балансы счетов вычисляемые — пересчитаются
     * сами). Позиции аренды удаляются JPA-каскадом.
     */
    @Transactional
    public void delete(UUID rentalId) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() == RentalStatus.DRAFT || rental.getStatus() == RentalStatus.ACTIVE) {
            throw new ConflictException("Удалить можно только завершённую или отменённую аренду");
        }
        // события ссылаются на операции по FK — сначала лента, потом операции
        rentalEventRepository.deleteByRentalId(rentalId);
        financeTransactionRepository.findAllByRentalIdOrderByDateDesc(rentalId)
                .forEach(financeTransactionRepository::delete);
        rentalExtensionRepository.deleteByRentalId(rentalId);
        rentalRepository.delete(rental);
    }

    /**
     * Продление: якорь = max(plannedEndAt, сейчас) — у просроченной аренды продлеваем от текущего
     * момента, иначе новый конец уехал бы в прошлое и сумма не выросла. Новый конец = якорь +
     * duration × unit. Продление хранится отдельной записью (можно править/удалять с пересчётом),
     * денег не двигает — оплата принимается отдельно через платежи. Событие со сдвигом срока — в ленту.
     */
    @Transactional
    public RentalResponse extend(UUID rentalId, ExtendRentalRequest request, User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("Продлить можно только активную аренду");
        }
        if (rental.getPlannedEndAt() == null) {
            throw new ConflictException("Продление доступно только для аренды с фиксированным сроком");
        }

        Instant anchor = rental.getPlannedEndAt().isAfter(Instant.now())
                ? rental.getPlannedEndAt() : Instant.now();
        Instant toEnd = anchor.plusSeconds(request.duration() * request.durationUnit().getSeconds());

        RentalExtension extension = new RentalExtension();
        extension.setRental(rental);
        extension.setDuration(request.duration());
        extension.setDurationUnit(request.durationUnit());
        extension.setFromEndAt(anchor);
        extension.setToEndAt(toEnd);
        extension.setCreatedBy(author);
        rentalExtensionRepository.save(extension);

        rental.setPlannedEndAt(toEnd);
        Rental saved = rentalRepository.save(rental);

        recordExtensionEvent(saved,
                "Продление на " + request.duration() + " × " + durationUnitLabel(request.durationUnit()),
                request.duration(), request.durationUnit(), anchor, toEnd, author);
        return toResponse(saved, Instant.now());
    }

    /**
     * Правка продления (только у активной аренды): длительность/единица меняются,
     * затем вся цепочка продлений пересчитывается от якоря самого раннего.
     */
    @Transactional
    public RentalResponse updateExtension(UUID rentalId, UUID extensionId, ExtensionRequest request,
                                          User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("Продления можно править только у активной аренды");
        }
        RentalExtension extension = findExtension(rentalId, extensionId);
        int oldDuration = extension.getDuration();
        TariffUnit oldUnit = extension.getDurationUnit();

        extension.setDuration(request.duration());
        extension.setDurationUnit(request.durationUnit());
        rentalExtensionRepository.save(extension);
        recalcExtensionChain(rental);
        rentalRepository.save(rental);

        recordExtensionEvent(rental,
                "Продление изменено: было " + oldDuration + " × " + durationUnitLabel(oldUnit)
                        + " → стало " + request.duration() + " × " + durationUnitLabel(request.durationUnit()),
                request.duration(), request.durationUnit(),
                extension.getFromEndAt(), extension.getToEndAt(), author);
        return toResponse(rental, Instant.now());
    }

    /**
     * Удаление продления (только у активной аренды): цепочка оставшихся пересчитывается;
     * если продлений не осталось — конец возвращается к якорю удалённого.
     */
    @Transactional
    public RentalResponse deleteExtension(UUID rentalId, UUID extensionId, User author) {
        Rental rental = findRental(rentalId);
        if (rental.getStatus() != RentalStatus.ACTIVE) {
            throw new ConflictException("Продления можно удалять только у активной аренды");
        }
        RentalExtension extension = findExtension(rentalId, extensionId);
        int duration = extension.getDuration();
        TariffUnit unit = extension.getDurationUnit();
        Instant deletedAnchor = extension.getFromEndAt();
        rentalExtensionRepository.delete(extension);

        List<RentalExtension> remaining =
                rentalExtensionRepository.findAllByRentalIdOrderByCreatedAtAsc(rentalId);
        if (remaining.isEmpty()) {
            rental.setPlannedEndAt(deletedAnchor);
        } else {
            recalcExtensionChain(rental, remaining);
        }
        rentalRepository.save(rental);

        recordExtensionEvent(rental,
                "Продление удалено (" + duration + " × " + durationUnitLabel(unit) + ")",
                duration, unit, null, null, author);
        return toResponse(rental, Instant.now());
    }

    /** Лента событий аренды (новые сверху). */
    @Transactional(readOnly = true)
    public List<RentalEventResponse> events(UUID rentalId) {
        if (!rentalRepository.existsById(rentalId)) {
            throw new NotFoundException("Аренда не найдена");
        }
        return rentalEventRepository.findAllByRentalIdOrderByDateDesc(rentalId).stream()
                .map(RentalEventResponse::from)
                .toList();
    }

    private Rental findRental(UUID id) {
        return rentalRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Аренда не найдена"));
    }

    private RentalExtension findExtension(UUID rentalId, UUID extensionId) {
        RentalExtension extension = rentalExtensionRepository.findById(extensionId)
                .orElseThrow(() -> new NotFoundException("Продление не найдено"));
        if (!extension.getRental().getId().equals(rentalId)) {
            throw new NotFoundException("Продление не найдено в этой аренде");
        }
        return extension;
    }

    /**
     * Пересчёт цепочки продлений по порядку создания: база — якорь (fromEndAt) самого раннего,
     * каждое следующее продлевается от конца предыдущего; итоговый конец — plannedEndAt аренды.
     */
    private void recalcExtensionChain(Rental rental) {
        recalcExtensionChain(rental,
                rentalExtensionRepository.findAllByRentalIdOrderByCreatedAtAsc(rental.getId()));
    }

    private void recalcExtensionChain(Rental rental, List<RentalExtension> extensions) {
        if (extensions.isEmpty()) {
            return;
        }
        Instant end = extensions.get(0).getFromEndAt();
        for (RentalExtension extension : extensions) {
            extension.setFromEndAt(end);
            end = end.plusSeconds(extension.getDuration() * extension.getDurationUnit().getSeconds());
            extension.setToEndAt(end);
        }
        rental.setPlannedEndAt(end);
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
        // оплачено — только приходы; возвраты клиенту — отдельным полем (блок «Возвраты»)
        int paidAmount = financeTransactionRepository.paidSumByRentalId(rental.getId());
        int refundedAmount = financeTransactionRepository.refundedSumByRentalId(rental.getId());
        List<RentalExtension> extensions =
                rentalExtensionRepository.findAllByRentalIdOrderByCreatedAtAsc(rental.getId());
        return RentalResponse.from(rental, now, paidAmount, refundedAmount, extensions);
    }

    /** Формат даты для текстов событий ленты (локальная зона сервера). */
    private static String fmt(Instant instant) {
        return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(instant);
    }

    /**
     * Досрочный возврат — только в календарные дни ДО дня окончания аренды и НЕ РАНЬШЕ
     * дня начала (локальная дата сервера). В день окончания или позже — это обычное
     * завершение (complete).
     */
    private void assertEarlyReturnDay(Rental rental, Instant returnedAt) {
        if (rental.getPlannedEndAt() == null) {
            return; // rent_to_own — без фиксированного конца
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate endDay = rental.getPlannedEndAt().atZone(zone).toLocalDate();
        LocalDate returnDay = returnedAt.atZone(zone).toLocalDate();
        if (!returnDay.isBefore(endDay)) {
            throw new ConflictException("Это не досрочный возврат: возврат в день окончания аренды "
                    + "или позже оформляется обычным завершением. Досрочный возврат возможен "
                    + "только в календарные дни до дня окончания аренды");
        }
        LocalDate startDay = rental.getStartAt().atZone(zone).toLocalDate();
        if (returnDay.isBefore(startDay)) {
            throw new ConflictException("Дата приёма раньше дня начала аренды");
        }
    }

    /** Завершение (обычное и досрочное) — только при полной оплате: оплачено >= начислено на дату приёма. */
    private void assertFullyPaid(Rental rental, Instant at) {
        int amount = rentalAmount(rental, at);
        int paid = financeTransactionRepository.paidSumByRentalId(rental.getId());
        if (paid < amount) {
            throw new ConflictException(
                    "Аренда не оплачена полностью: не хватает " + (amount - paid) + " ₽");
        }
    }

    /** Начислено по аренде на момент at (rent — позиции по тарифу, rent_to_own — цена выкупа). */
    private static int rentalAmount(Rental rental, Instant at) {
        return RentalAmounts.accrued(rental, at);
    }

    /**
     * Обычное завершение — только в календарный день окончания аренды (локальная дата сервера).
     * Позже: считаем доплату и отправляем продлевать; раньше: это сценарий досрочного возврата.
     */
    private void assertSameCalendarDay(Rental rental, Instant returnedAt) {
        if (rental.getPlannedEndAt() == null) {
            return; // rent_to_own — без фиксированного конца
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate endDay = rental.getPlannedEndAt().atZone(zone).toLocalDate();
        LocalDate returnDay = returnedAt.atZone(zone).toLocalDate();
        if (returnDay.equals(endDay)) {
            return;
        }
        if (returnDay.isBefore(endDay)) {
            throw new ConflictException("Дата приёма раньше дня окончания аренды. Если клиент вернул "
                    + "технику раньше и просит деньги — оформите «Вернуть досрочно»");
        }
        int extra = rentalAmount(rental, returnedAt) - rentalAmount(rental, rental.getPlannedEndAt());
        String suggestion = rental.getItems().stream()
                .filter(item -> item.getParentItem() == null)
                .findFirst()
                .map(item -> {
                    long gapSeconds = Math.max(1,
                            returnedAt.getEpochSecond() - rental.getPlannedEndAt().getEpochSecond());
                    long unitSeconds = item.getTariffUnit().getSeconds();
                    long units = (gapSeconds + unitSeconds - 1) / unitSeconds;
                    return " Продлите аренду на " + units + " × "
                            + durationUnitLabel(item.getTariffUnit()) + ",";
                })
                .orElse("");
        throw new ConflictException("Возврат позже дня окончания аренды: доплата " + extra + " ₽."
                + suggestion + " примите доплату — после этого аренду можно завершить");
    }

    /** Приходная/расходная операция по аренде (оплата, продление, возврат денег). */
    private FinanceTransaction recordRentalTransaction(Rental rental, CategoryKind kind, String categoryName,
                                                       int amount, UUID accountId, String comment, User author) {
        return recordRentalTransaction(rental, kind, categoryName, amount, accountId, comment, author,
                Instant.now());
    }

    private FinanceTransaction recordRentalTransaction(Rental rental, CategoryKind kind, String categoryName,
                                                       int amount, UUID accountId, String comment, User author,
                                                       Instant date) {
        FinanceAccount account = financeAccountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("Счёт не найден"));
        FinanceCategory category = financeCategoryRepository
                .findByNameAndKind(categoryName, kind)
                .orElseGet(() -> {
                    FinanceCategory created = new FinanceCategory();
                    created.setName(categoryName);
                    created.setKind(kind);
                    return financeCategoryRepository.save(created);
                });
        FinanceTransaction transaction = new FinanceTransaction();
        transaction.setAccount(account);
        transaction.setCategory(category);
        transaction.setKind(kind);
        transaction.setAmount(amount);
        transaction.setDate(date);
        transaction.setComment(comment);
        transaction.setRental(rental);
        transaction.setCreatedBy(author);
        return financeTransactionRepository.save(transaction);
    }

    /** Событие продления в ленту: создание, правка (from/to — новые), удаление (from/to — null). */
    private void recordExtensionEvent(Rental rental, String comment, Integer duration, TariffUnit unit,
                                      Instant fromEndAt, Instant toEndAt, User author) {
        RentalEvent event = new RentalEvent();
        event.setRental(rental);
        event.setType(RentalEventType.EXTENSION);
        event.setDate(Instant.now());
        event.setComment(comment);
        event.setDuration(duration);
        event.setDurationUnit(unit);
        event.setFromEndAt(fromEndAt);
        event.setToEndAt(toEndAt);
        event.setCreatedBy(author);
        rentalEventRepository.save(event);
    }

    private void recordEvent(Rental rental, RentalEventType type, String comment, Integer amount,
                             FinanceTransaction transaction, User author) {
        recordEvent(rental, type, comment, amount, transaction, author, null);
    }

    /** Событие ленты: date — фактический момент записи, docDate — дата «по документам». */
    private void recordEvent(Rental rental, RentalEventType type, String comment, Integer amount,
                             FinanceTransaction transaction, User author, Instant docDate) {
        RentalEvent event = new RentalEvent();
        event.setRental(rental);
        event.setType(type);
        event.setDate(Instant.now());
        event.setDocDate(docDate);
        event.setComment(comment);
        event.setAmount(amount);
        event.setTransaction(transaction);
        event.setCreatedBy(author);
        rentalEventRepository.save(event);
    }

    private static String durationUnitLabel(TariffUnit unit) {
        return switch (unit) {
            case HOUR -> "час";
            case DAY -> "день";
            case WEEK -> "неделя";
            case MONTH -> "месяц";
        };
    }
}
