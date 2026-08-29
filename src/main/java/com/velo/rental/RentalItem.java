package com.velo.rental;

import com.velo.asset.Asset;
import com.velo.tariff.TariffUnit;
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
@Table(name = "rental_items")
@Getter
@Setter
@NoArgsConstructor
public class RentalItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    /** Родительская позиция (комплект: АКБ, смонтированная на велосипеде-родителе). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_item_id")
    private RentalItem parentItem;

    /** Снапшот тарифа на момент выдачи: цена за единицу + единица. */
    @Column(name = "rate", nullable = false)
    private int rate = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "tariff_unit", nullable = false, length = 10)
    private TariffUnit tariffUnit = TariffUnit.HOUR;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Стоимость позиции: начатые единицы тарифа (минимум 1) × цена. */
    public int amount(Instant now) {
        Instant end = returnedAt != null ? returnedAt : now;
        long seconds = Math.max(0, end.getEpochSecond() - rental.getStartAt().getEpochSecond());
        long unitSeconds = tariffUnit.getSeconds();
        long units = Math.max(1, (seconds + unitSeconds - 1) / unitSeconds);
        return Math.toIntExact(units) * rate;
    }
}
