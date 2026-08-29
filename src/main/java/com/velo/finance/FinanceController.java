package com.velo.finance;

import com.velo.finance.dto.AccountOptionResponse;
import com.velo.finance.dto.AccountResponse;
import com.velo.finance.dto.CategoryResponse;
import com.velo.finance.dto.CreateAccountRequest;
import com.velo.finance.dto.CreateCategoryRequest;
import com.velo.finance.dto.CreateTransactionRequest;
import com.velo.finance.dto.TransactionResponse;
import com.velo.finance.dto.UpdateAccountRequest;
import com.velo.finance.dto.UpdateTransactionRequest;
import com.velo.security.AppPermissions;
import com.velo.user.Role;
import com.velo.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceController {

    private static final String CAN_VIEW_FINANCES =
            "hasRole('ADMIN') or hasAuthority('" + AppPermissions.FINANCE_VIEW + "')";

    private final FinanceService financeService;

    @GetMapping("/accounts")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public List<AccountResponse> findAccounts() {
        return financeService.findAccounts();
    }

    /** Только id + название, для селектов (заведение актива со списанием и т.п.). Без остатков. */
    @GetMapping("/accounts/options")
    public List<AccountOptionResponse> findAccountOptions() {
        return financeService.findAccountOptions();
    }

    @PostMapping("/accounts")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createAccount(request));
    }

    @PatchMapping("/accounts/{id}")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public AccountResponse updateAccount(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateAccountRequest request) {
        return financeService.updateAccount(id, request);
    }

    @DeleteMapping("/accounts/{id}")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public ResponseEntity<Void> deleteAccount(@PathVariable UUID id) {
        financeService.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }

    /** Статьи видны всем сотрудникам — нужны для приёма прихода. */
    @GetMapping("/categories")
    public List<CategoryResponse> findCategories() {
        return financeService.findCategories();
    }

    @PostMapping("/categories")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(financeService.createCategory(request));
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        financeService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/transactions")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public List<TransactionResponse> findTransactions(@RequestParam(required = false) UUID accountId,
                                                      @RequestParam(required = false) CategoryKind kind) {
        return financeService.findTransactions(accountId, kind);
    }

    /** Приход может провести любой сотрудник (приём денег), расход — только с правом. */
    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> createTransaction(
            @Valid @RequestBody CreateTransactionRequest request,
            @AuthenticationPrincipal User author) {
        boolean canViewFinances = author.getRole() == Role.ADMIN
                || author.getPermissions().contains(AppPermissions.FINANCE_VIEW);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(financeService.createTransaction(request, author, canViewFinances));
    }

    /** Правка и удаление истории — только тем, кто видит финансы. */
    @PatchMapping("/transactions/{id}")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public TransactionResponse updateTransaction(@PathVariable UUID id,
                                                 @Valid @RequestBody UpdateTransactionRequest request) {
        return financeService.updateTransaction(id, request);
    }

    @DeleteMapping("/transactions/{id}")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public ResponseEntity<Void> deleteTransaction(@PathVariable UUID id) {
        financeService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }
}
