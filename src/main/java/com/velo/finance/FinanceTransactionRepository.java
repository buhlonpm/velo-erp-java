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

    /** Операция покупки SIM-карты. */
    java.util.Optional<FinanceTransaction> findFirstBySimCard_IdAndCategory_Name(UUID simCardId,
                                                                                 String categoryName);

    /** Операция покупки GPS-трекера. */
    java.util.Optional<FinanceTransaction> findFirstByGpsTracker_IdAndCategory_Name(UUID trackerId,
                                                                                    String categoryName);

    /** Сумма приходов минус сумма расходов по счёту. */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.kind = com.velo.finance.CategoryKind.INCOME
                                     THEN t.amount ELSE -t.amount END), 0)
            FROM FinanceTransaction t
            WHERE t.account.id = :accountId
            """)
    int balanceDeltaByAccountId(@Param("accountId") UUID accountId);

    /** Оплачено по аренде: только приходы (оплаты клиента). Возвраты — отдельно, см. refundedSumByRentalId. */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.kind = com.velo.finance.CategoryKind.INCOME
                                     THEN t.amount ELSE 0 END), 0)
            FROM FinanceTransaction t
            WHERE t.rental.id = :rentalId
            """)
    int paidSumByRentalId(@Param("rentalId") UUID rentalId);

    /** Возвращено клиенту по аренде: расходные операции с rental_id. */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN t.kind = com.velo.finance.CategoryKind.EXPENSE
                                     THEN t.amount ELSE 0 END), 0)
            FROM FinanceTransaction t
            WHERE t.rental.id = :rentalId
            """)
    int refundedSumByRentalId(@Param("rentalId") UUID rentalId);

    List<FinanceTransaction> findAllByAssetIdOrderByDateDesc(UUID assetId);

    /** Операции по аренде (история оплат в карточке аренды). */
    List<FinanceTransaction> findAllByRentalIdOrderByDateDesc(UUID rentalId);

    /** Операции по аренде заданного типа (оплаты — INCOME, возвраты — EXPENSE). */
    List<FinanceTransaction> findAllByRentalIdAndKindOrderByDateDesc(UUID rentalId, CategoryKind kind);

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
