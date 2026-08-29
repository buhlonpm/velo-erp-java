package com.velo.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FinanceTransactionRepository extends JpaRepository<FinanceTransaction, UUID> {

    List<FinanceTransaction> findAllByOrderByDateDesc();

    List<FinanceTransaction> findAllByAccountIdOrderByDateDesc(UUID accountId);

    List<FinanceTransaction> findAllByKindOrderByDateDesc(CategoryKind kind);

    List<FinanceTransaction> findAllByAccountIdAndKindOrderByDateDesc(UUID accountId, CategoryKind kind);

    boolean existsByCategoryId(UUID categoryId);

    boolean existsByAccountId(UUID accountId);

    /** Операция покупки актива (привязка к активу + статья «Покупка оборудования»). */
    java.util.Optional<FinanceTransaction> findFirstByAsset_IdAndCategory_Name(UUID assetId, String categoryName);

    /** Сумма приходов минус сумма расходов по счёту. */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.kind = com.velo.finance.CategoryKind.INCOME
                                     THEN t.amount ELSE -t.amount END), 0)
            FROM FinanceTransaction t
            WHERE t.account.id = :accountId
            """)
    int balanceDeltaByAccountId(@Param("accountId") UUID accountId);

    /** Сколько внесено по аренде (приходные операции с rental_id). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM FinanceTransaction t
            WHERE t.rental.id = :rentalId AND t.kind = com.velo.finance.CategoryKind.INCOME
            """)
    int incomeSumByRentalId(@Param("rentalId") UUID rentalId);

    List<FinanceTransaction> findAllByAssetIdOrderByDateDesc(UUID assetId);

    /** Сумма операций по активу заданного типа (INCOME/EXPENSE). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM FinanceTransaction t
            WHERE t.asset.id = :assetId AND t.kind = :kind
            """)
    int sumByAssetIdAndKind(@Param("assetId") UUID assetId, @Param("kind") CategoryKind kind);

    /** То же, но без операций указанной статьи (покупка считается отдельно, иначе двойной счёт). */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM FinanceTransaction t
            WHERE t.asset.id = :assetId AND t.kind = :kind AND t.category.name <> :excludeCategory
            """)
    int sumByAssetIdAndKindExcludingCategory(@Param("assetId") UUID assetId,
                                             @Param("kind") CategoryKind kind,
                                             @Param("excludeCategory") String excludeCategory);
}
