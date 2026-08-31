package com.velo.finance;

import com.velo.asset.Asset;
import com.velo.rental.Rental;
import com.velo.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finance_transactions")
@Getter
@Setter
@NoArgsConstructor
public class FinanceTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private FinanceAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private FinanceCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CategoryKind kind;

    /** Всегда положительная; знак определяется kind. */
    @Column(nullable = false)
    private int amount;

    @Column(nullable = false)
    private Instant date;

    @Column(nullable = false)
    private String comment = "";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /** Привязка к аренде: оплата аренды, выкупные платежи. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rental_id")
    private Rental rental;

    /** Привязка к активу: ремонт конкретного велосипеда, выплата за повреждение и т.п. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id")
    private Asset asset;

    /** Привязка к SIM-карте: системная операция покупки симки. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sim_card_id")
    private com.velo.gps.SimCard simCard;

    /** Привязка к GPS-трекеру: системная операция покупки трекера. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gps_tracker_id")
    private com.velo.gps.GpsTracker gpsTracker;

    /** Системная операция создана доменным действием (покупка/продажа техники) — не правится и не удаляется вручную. */
    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    /** Стратегия переплаты, выбранная при приёме оплаты выкупа (rent_to_own). Хранится на
     *  операции, чтобы график пересчитывался с нуля (replay) после правки/удаления оплат. */
    @Enumerated(EnumType.STRING)
    @Column(name = "overpayment_strategy")
    private com.velo.rental.OverpaymentStrategy overpaymentStrategy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
