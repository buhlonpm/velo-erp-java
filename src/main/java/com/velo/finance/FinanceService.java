package com.velo.finance;

import com.velo.asset.Asset;
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
import com.velo.rental.RentalEventRepository;
import com.velo.rental.RentalRepository;
import com.velo.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
        if (transactionRepository.existsByCategoryId(id)) {
            throw new ConflictException("По статье есть операции — удалить нельзя");
        }
        categoryRepository.delete(category);
    }

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
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    /**
     * Правка операции. Доступ — только с правом finance:view (контроллер).
     * Привязка к аренде не редактируется.
     * Системные операции (покупка/продажа техники) не правятся — меняется само доменное действие.
     */
    @Transactional
    public TransactionResponse updateTransaction(UUID id, UpdateTransactionRequest request) {
        FinanceTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Операция не найдена"));
        if (transaction.isSystem()) {
            throw new ConflictException("Это системная операция — она создана автоматически "
                    + "(покупка/продажа техники) и не редактируется");
        }
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
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    /**
     * Удаление операции. Баланс пересчитывать не нужно — он вычисляется
     * от списка операций, поэтому всегда корректен после удаления любой,
     * в том числе давнишней. Ссылку на операцию в событиях аренды снимаем — событие остаётся.
     * Системные операции (покупка/продажа техники) не удаляются — иначе техника
     * останется в системе, а расход/приход по ней исчезнет.
     */
    @Transactional
    public void deleteTransaction(UUID id) {
        FinanceTransaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Операция не найдена"));
        if (transaction.isSystem()) {
            throw new ConflictException("Это системная операция — она создана автоматически "
                    + "(покупка/продажа техники) и не удаляется. Отменяйте само действие: "
                    + "списание/восстановление актива, трекера или SIM-карты");
        }
        rentalEventRepository.clearTransactionReference(id);
        transactionRepository.delete(transaction);
    }
}
