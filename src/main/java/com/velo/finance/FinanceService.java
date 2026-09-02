package com.velo.finance;

import com.velo.asset.Asset;
import com.velo.asset.AssetEventRepository;
import com.velo.asset.AssetEventService;
import com.velo.asset.AssetEventType;
import com.velo.asset.AssetRepository;
import com.velo.common.exception.ConflictException;
import com.velo.common.exception.NotFoundException;
import com.velo.finance.dto.AccountOptionResponse;
import com.velo.finance.dto.AccountResponse;
import com.velo.finance.dto.CategoryResponse;
import com.velo.finance.dto.CreateAccountRequest;
import com.velo.finance.dto.CreateCategoryRequest;
import com.velo.finance.dto.CreateTransactionRequest;
import com.velo.finance.dto.TransactionResponse;
import com.velo.finance.dto.UpdateAccountRequest;
import com.velo.finance.dto.UpdateTransactionRequest;
import com.velo.rental.Rental;
import com.velo.rental.RentalEvent;
import com.velo.rental.RentalEventRepository;
import com.velo.rental.RentalEventType;
import com.velo.rental.RentalKind;
import com.velo.rental.RentalRepository;
import com.velo.rental.RentalSchedule;
import com.velo.rental.RentalStatus;
import com.velo.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
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
public class FinanceService {

    private final FinanceAccountRepository accountRepository;
    private final FinanceCategoryRepository categoryRepository;
    private final FinanceTransactionRepository transactionRepository;
    private final RentalRepository rentalRepository;
    private final RentalEventRepository rentalEventRepository;
    private final AssetRepository assetRepository;
    private final AssetEventService assetEventService;
    private final AssetEventRepository assetEventRepository;

    public List<AccountResponse> findAccounts() {
        return accountRepository.findAllByOrderByCreatedAt().stream()
                .map(account -> AccountResponse.from(account,
                        transactionRepository.balanceDeltaByAccountId(account.getId()),
                        transactionRepository.existsByAccountId(account.getId())))
                .toList();
    }

    /** Лёгкий список счетов для селектов — без остатков, доступен всем сотрудникам. */
    public List<AccountOptionResponse> findAccountOptions() {
        return accountRepository.findAllByOrderByCreatedAt().stream()
                .map(AccountOptionResponse::from)
                .toList();
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        FinanceAccount account = new FinanceAccount();
        account.setName(request.name());
        account.setType(request.type());
        return AccountResponse.from(accountRepository.save(account), 0, false);
    }

    @Transactional
    public AccountResponse updateAccount(UUID id, UpdateAccountRequest request) {
        FinanceAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Счёт не найден"));
        if (request.name() != null) {
            account.setName(request.name());
        }
        if (request.type() != null) {
            account.setType(request.type());
        }
        FinanceAccount saved = accountRepository.save(account);
        return AccountResponse.from(saved, transactionRepository.balanceDeltaByAccountId(id),
                transactionRepository.existsByAccountId(id));
    }

    @Transactional
    public void deleteAccount(UUID id) {
        FinanceAccount account = accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Счёт не найден"));
        if (transactionRepository.existsByAccountId(id)) {
            throw new ConflictException("По счёту есть операции — удалить нельзя");
        }
        accountRepository.delete(account);
    }

    public List<CategoryResponse> findCategories() {
        return categoryRepository.findAllByOrderByKindAscCreatedAtAsc().stream()
                .map(category -> CategoryResponse.from(category,
                        transactionRepository.existsByCategoryId(category.getId())))
                .toList();
    }

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        String name = request.name().trim();
        if (categoryRepository.existsByNameAndKind(name, request.kind())) {
            throw new ConflictException("Такая статья уже существует");
        }
        FinanceCategory category = new FinanceCategory();
        category.setName(name);
        category.setKind(request.kind());
        return CategoryResponse.from(categoryRepository.save(category), false);
    }

    @Transactional
    public void deleteCategory(UUID id) {
        FinanceCategory category = categoryRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Статья не найдена"));
        if (category.isSystem()) {
            throw new ConflictException("Это системная статья — она не удаляется");
        }
        if (transactionRepository.existsByCategoryId(id)) {
            throw new ConflictException("По статье есть операции — удалить нельзя");
        }
        categoryRepository.delete(category);
    }

    @Transactional(readOnly = true) // rentalStatus в ответе инициализирует прокси аренды — нужна сессия
    public List<TransactionResponse> findTransactions(UUID accountId, CategoryKind kind, UUID rentalId) {
        List<FinanceTransaction> transactions;
        if (rentalId != null && kind != null) {
            // история оплат/возвратов конкретной аренды (карточка аренды)
            transactions = transactionRepository.findAllByRentalIdAndKindOrderByDateDesc(rentalId, kind);
        } else if (rentalId != null) {
            transactions = transactionRepository.findAllByRentalIdOrderByDateDesc(rentalId);
        } else if (accountId != null && kind != null) {
            transactions = transactionRepository.findAllByAccountIdAndKindOrderByDateDesc(accountId, kind);
        } else if (accountId != null) {
            transactions = transactionRepository.findAllByAccountIdOrderByDateDesc(accountId);
        } else if (kind != null) {
            transactions = transactionRepository.findAllByKindOrderByDateDesc(kind);
        } else {
            transactions = transactionRepository.findAllByOrderByDateDesc();
        }
        return transactions.stream().map(TransactionResponse::from).toList();
    }

    /**
     * Создать операцию. Приход может провести любой сотрудник (приём денег),
     * расход — только обладатель права finance:view или ADMIN (проверяется в контроллере).
     */
    @Transactional
    public TransactionResponse createTransaction(CreateTransactionRequest request,
                                                 User author,
                                                 boolean canViewFinances) {
        if (request.kind() == CategoryKind.EXPENSE && !canViewFinances) {
            throw new AccessDeniedException("Расходные операции доступны только с правом «финансы»");
        }
        FinanceAccount account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new NotFoundException("Счёт не найден"));
        FinanceCategory category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NotFoundException("Статья не найдена"));
        if (category.getKind() != request.kind()) {
            throw new ConflictException("Тип операции не совпадает с типом статьи");
        }

        FinanceTransaction transaction = new FinanceTransaction();
        transaction.setAccount(account);
        transaction.setCategory(category);
        transaction.setKind(request.kind());
        transaction.setAmount(request.amount());
        transaction.setDate(Instant.now());
        transaction.setComment(request.comment() != null ? request.comment() : "");
        transaction.setCreatedBy(author);
        if (request.rentalId() != null) {
            Rental rental = rentalRepository.findById(request.rentalId())
                    .orElseThrow(() -> new NotFoundException("Аренда не найдена"));
            transaction.setRental(rental);
        }
        if (request.assetId() != null) {
            Asset asset = assetRepository.findById(request.assetId())
                    .orElseThrow(() -> new NotFoundException("Актив не найден"));
            transaction.setAsset(asset);
        }
        FinanceTransaction saved = transactionRepository.save(transaction);
        recordAssetCreateEvent(saved, author);
        reallocateBuyoutSchedule(saved);
        return TransactionResponse.from(saved);
    }

    /**
     * Правка операции. Доступ — только с правом finance:view (контроллер).
     * Привязка к аренде не редактируется.
     * Системные операции (покупка/продажа техники) не правятся — меняется само доменное действие.
     * Если операция привязана к аренде и поменялась сумма или дата — пишем событие в ленту аренды.
     */
    @Transactional
    public TransactionResponse updateTransaction(UUID id, UpdateTransactionRequest request, User author) {
        FinanceTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Операция не найдена"));
        if (transaction.isSystem()) {
            throw new ConflictException("Это системная операция — она создана автоматически "
                    + "(покупка/продажа техники) и не редактируется");
        }
        assertRentalNotFinished(transaction);
        int oldAmount = transaction.getAmount();
        Instant oldDate = transaction.getDate();
        if (request.accountId() != null) {
            FinanceAccount account = accountRepository.findById(request.accountId())
                    .orElseThrow(() -> new NotFoundException("Счёт не найден"));
            transaction.setAccount(account);
        }
        if (request.categoryId() != null) {
            FinanceCategory category = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new NotFoundException("Статья не найдена"));
            if (category.getKind() != transaction.getKind()) {
                throw new ConflictException("Тип статьи не совпадает с типом операции");
            }
            transaction.setCategory(category);
        }
        if (request.amount() != null) {
            transaction.setAmount(request.amount());
        }
        if (request.date() != null) {
            transaction.setDate(request.date());
        }
        if (request.comment() != null) {
            transaction.setComment(request.comment());
        }
        TransactionResponse response = TransactionResponse.from(transactionRepository.save(transaction));
        recordChangeEvent(transaction, oldAmount, oldDate, author);
        recordAssetChangeEvent(transaction, oldAmount, oldDate, author);
        reallocateBuyoutSchedule(transaction);
        return response;
    }

    /**
     * Событие в ленту актива при создании привязанной операции — иначе приходы/расходы
     * по активу в его истории не видны. Системные операции (покупка/продажа) сюда не попадают:
     * они создаются доменными действиями и уже дают события purchase/write_off.
     */
    private void recordAssetCreateEvent(FinanceTransaction transaction, User author) {
        Asset asset = transaction.getAsset();
        if (asset == null) {
            return;
        }
        boolean income = transaction.getKind() == CategoryKind.INCOME;
        String comment = (income ? "Приход: " : "Расход: ") + formatMoney(transaction.getAmount())
                + " ₽ · " + transaction.getCategory().getName();
        if (!transaction.getComment().isBlank()) {
            comment += " · " + transaction.getComment();
        }
        assetEventService.record(asset, income ? AssetEventType.INCOME : AssetEventType.EXPENSE,
                comment, transaction.getAmount(), transaction, author);
    }

    /** Событие в ленту актива при правке привязанной операции: что именно изменилось (сумма и/или дата). */
    private void recordAssetChangeEvent(FinanceTransaction transaction, int oldAmount, Instant oldDate,
                                        User author) {
        Asset asset = transaction.getAsset();
        if (asset == null) {
            return;
        }
        boolean amountChanged = transaction.getAmount() != oldAmount;
        boolean dateChanged = !transaction.getDate().equals(oldDate);
        if (!amountChanged && !dateChanged) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (amountChanged) {
            parts.add("сумма: " + formatMoney(oldAmount) + " ₽ → " + formatMoney(transaction.getAmount()) + " ₽");
        }
        if (dateChanged) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault());
            parts.add("дата: " + formatter.format(oldDate) + " → " + formatter.format(transaction.getDate()));
        }
        boolean income = transaction.getKind() == CategoryKind.INCOME;
        assetEventService.record(asset, income ? AssetEventType.INCOME : AssetEventType.EXPENSE,
                (income ? "Приход изменён: " : "Расход изменён: ") + String.join("; ", parts),
                transaction.getAmount(), transaction, author);
    }

    /** Событие в ленту актива при удалении привязанной операции — иначе факт удаления теряется. */
    private void recordAssetDeleteEvent(FinanceTransaction transaction, User author) {
        Asset asset = transaction.getAsset();
        if (asset == null) {
            return;
        }
        boolean income = transaction.getKind() == CategoryKind.INCOME;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault());
        assetEventService.record(asset, income ? AssetEventType.INCOME : AssetEventType.EXPENSE,
                (income ? "Приход удалён: " : "Расход удалён: ") + formatMoney(transaction.getAmount())
                        + " ₽ · " + transaction.getCategory().getName()
                        + "; дата: " + formatter.format(transaction.getDate()),
                transaction.getAmount(), null, author);
    }

    /** Событие в ленту аренды при правке оплаты/возврата: что именно изменилось (сумма и/или дата). */
    private void recordChangeEvent(FinanceTransaction transaction, int oldAmount, Instant oldDate, User author) {
        if (transaction.getRental() == null) {
            return;
        }
        boolean amountChanged = transaction.getAmount() != oldAmount;
        boolean dateChanged = !transaction.getDate().equals(oldDate);
        if (!amountChanged && !dateChanged) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (amountChanged) {
            parts.add("сумма: " + formatMoney(oldAmount) + " ₽ → " + formatMoney(transaction.getAmount()) + " ₽");
        }
        if (dateChanged) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .withZone(ZoneId.systemDefault());
            parts.add("дата: " + formatter.format(oldDate) + " → " + formatter.format(transaction.getDate()));
        }
        RentalEvent event = new RentalEvent();
        event.setRental(transaction.getRental());
        event.setType(transaction.getKind() == CategoryKind.INCOME ? RentalEventType.PAYMENT : RentalEventType.REFUND);
        event.setDate(Instant.now());
        event.setDocDate(transaction.getDate());
        event.setComment((transaction.getKind() == CategoryKind.INCOME ? "Оплата изменена: " : "Возврат изменён: ")
                + String.join("; ", parts));
        event.setAmount(transaction.getAmount());
        event.setTransaction(transaction);
        event.setCreatedBy(author);
        rentalEventRepository.save(event);
    }

    /**
     * Операции по завершённой аренде заморожены: сумма аренды зафиксирована как
     * «оплачено − возвращено», поэтому правки/удаления после закрытия — 409.
     */
    private static void assertRentalNotFinished(FinanceTransaction transaction) {
        Rental rental = transaction.getRental();
        if (rental != null && (rental.getStatus() == RentalStatus.COMPLETED
                || rental.getStatus() == RentalStatus.COMPLETED_EARLY)) {
            throw new ConflictException("Аренда завершена — операции по ней не правятся "
                    + "и не удаляются");
        }
    }

    private static String formatMoney(int value) {
        String digits = Integer.toString(value);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                result.append(' ');
            }
            result.append(digits.charAt(i));
        }
        return result.toString();
    }

    /**
     * Удаление операции. Баланс пересчитывать не нужно — он вычисляется
     * от списка операций, поэтому всегда корректен после удаления любой,
     * в том числе давнишней. Ссылку на операцию в событиях аренды снимаем — событие остаётся.
     * Системные операции (покупка/продажа техники) не удаляются — иначе техника
     * останется в системе, а расход/приход по ней исчезнет.
     */
    @Transactional
    public void deleteTransaction(UUID id, User author) {
        FinanceTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Операция не найдена"));
        if (transaction.isSystem()) {
            throw new ConflictException("Это системная операция — она создана автоматически "
                    + "(покупка/продажа техники) и не удаляется. Отменяйте само действие: "
                    + "списание/восстановление актива, трекера или SIM-карты");
        }
        assertRentalNotFinished(transaction);
        recordDeleteEvent(transaction, author);
        recordAssetDeleteEvent(transaction, author);
        rentalEventRepository.clearTransactionReference(id);
        assetEventRepository.clearTransactionReference(id);
        transactionRepository.delete(transaction);
        reallocateBuyoutSchedule(transaction);
    }

    /**
     * Пересчёт графика платежей выкупа после правки/удаления оплаты — полный replay с нуля:
     * исходный график от текущих условий + все оставшиеся оплаты в хронологии с их стратегиями
     * (стратегия хранится на операции). Удалённая ошибочная оплата выпадает из истории —
     * график возвращается к виду «как будто её не было».
     */
    private void reallocateBuyoutSchedule(FinanceTransaction transaction) {
        Rental rental = transaction.getRental();
        if (rental == null || rental.getKind() != RentalKind.RENT_TO_OWN) {
            return;
        }
        RentalSchedule.replay(rental, transactionRepository
                .findAllByRentalIdAndKindOrderByDateAscCreatedAtAsc(rental.getId(), CategoryKind.INCOME));
    }

    /** Событие в ленту аренды при удалении оплаты/возврата — иначе факт удаления теряется. */
    private void recordDeleteEvent(FinanceTransaction transaction, User author) {
        if (transaction.getRental() == null) {
            return;
        }
        boolean income = transaction.getKind() == CategoryKind.INCOME;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault());
        RentalEvent event = new RentalEvent();
        event.setRental(transaction.getRental());
        event.setType(income ? RentalEventType.PAYMENT : RentalEventType.REFUND);
        event.setDate(Instant.now());
        event.setDocDate(transaction.getDate());
        event.setComment((income ? "Оплата удалена: " : "Возврат удалён: ")
                + formatMoney(transaction.getAmount()) + " ₽; дата: " + formatter.format(transaction.getDate()));
        event.setAmount(transaction.getAmount());
        event.setCreatedBy(author);
        rentalEventRepository.save(event);
    }
}
