package com.velo.gps;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "sim_cards")
@Getter
@Setter
@NoArgsConstructor
public class SimCard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "phone_number", nullable = false, unique = true, length = 32)
    private String phoneNumber;

    @Column(nullable = false, length = 50)
    private String operator;

    @Column(nullable = false)
    private String note = "";

    @Column(name = "purchased_at")
    private Instant purchasedAt;

    /** 0 — шла в комплекте с трекером. */
    @Column(name = "purchase_price")
    private Integer purchasePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SimCardStatus status = SimCardStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "write_off_reason", length = 20)
    private com.velo.asset.WriteOffReason writeOffReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
