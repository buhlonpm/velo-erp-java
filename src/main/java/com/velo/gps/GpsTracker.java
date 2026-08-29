package com.velo.gps;

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
@Table(name = "gps_trackers")
@Getter
@Setter
@NoArgsConstructor
public class GpsTracker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(length = 32)
    private String imei;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sim_card_id")
    private SimCard simCard;

    @Column(name = "purchased_at")
    private Instant purchasedAt;

    @Column(name = "purchase_price")
    private Integer purchasePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GpsTrackerStatus status = GpsTrackerStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "write_off_reason", length = 20)
    private com.velo.asset.WriteOffReason writeOffReason;

    @Column(name = "write_off_comment", columnDefinition = "TEXT")
    private String writeOffComment;

    /** С какого велосипеда списан (NULL — не списан / был на складе). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "written_off_from_bike_id")
    private com.velo.asset.BikeAsset writtenOffFromBike;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
