package com.velo.rental;

import com.velo.customer.Customer;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "rentals")
@Getter
@Setter
@NoArgsConstructor
public class Rental {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RentalKind kind = RentalKind.RENT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RentalStatus status = RentalStatus.DRAFT;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "planned_end_at")
    private Instant plannedEndAt;

    @Column(nullable = false)
    private int deposit = 0;

    @Column(name = "buyout_price")
    private Integer buyoutPrice;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String comment = "";

    @OneToMany(mappedBy = "rental", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<RentalItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Вычисляемый статус для отображения: просроченная активная аренда. */
    public String displayStatus(Instant now) {
        if (status == RentalStatus.ACTIVE && plannedEndAt != null && plannedEndAt.isBefore(now)) {
            return "overdue";
        }
        return switch (status) {
            case DRAFT -> "draft";
            case ACTIVE -> "active";
            case COMPLETED -> "completed";
            case COMPLETED_EARLY -> "completed_early";
            case CANCELLED -> "cancelled";
        };
    }
}
