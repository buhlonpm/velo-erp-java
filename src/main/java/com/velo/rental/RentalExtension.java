package com.velo.rental;

import com.velo.tariff.TariffUnit;
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

/**
 * Продление аренды — отдельная запись, чтобы продление можно было править/удалять
 * с пересчётом срока по цепочке продлений. Якорь (fromEndAt) = max(plannedEndAt, момент
 * продления): у просроченной аренды продлеваем от «сейчас», иначе срок уехал бы в прошлое.
 */
@Entity
@Table(name = "rental_extensions")
@Getter
@Setter
@NoArgsConstructor
public class RentalExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;

    /** На сколько продлили. */
    @Column(nullable = false)
    private int duration;

    @Enumerated(EnumType.STRING)
    @Column(name = "duration_unit", nullable = false, length = 10)
    private TariffUnit durationUnit;

    /** Конец периода, от которого отталкивается продление (якорь). */
    @Column(name = "from_end_at", nullable = false)
    private Instant fromEndAt;

    /** Новый конец периода после продления. */
    @Column(name = "to_end_at", nullable = false)
    private Instant toEndAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
