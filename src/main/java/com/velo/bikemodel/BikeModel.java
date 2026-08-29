package com.velo.bikemodel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bike_models")
@Getter
@Setter
@NoArgsConstructor
public class BikeModel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String model;

    @Column(nullable = false)
    private String specs = "";

    /** Ресурс модели, км — для расчёта износа в карточке велосипеда (NULL — не задан). */
    @Column(name = "max_mileage_km")
    private Integer maxMileageKm;

    /** Ожидаемая цена продажи при макс. пробеге, % от цены покупки (NULL — не задан). */
    @Column(name = "residual_percent")
    private Integer residualPercent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
