package com.velo.tariff;

import com.velo.bikemodel.BikeModel;
import com.velo.rental.RentalKind;
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
@Table(name = "tariffs")
@Getter
@Setter
@NoArgsConstructor
public class Tariff {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Тариф принадлежит модели велосипеда. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private BikeModel model;

    /** Для какого вида договора тариф: аренда или выкуп (у выкупа строго unit=week, один на модель). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RentalKind kind = RentalKind.RENT;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TariffUnit unit;

    /** Цена за единицу (час/день/неделя/месяц), ₽. */
    @Column(nullable = false)
    private int price;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
