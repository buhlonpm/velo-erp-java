package com.velo.rental;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Строка графика платежей договора «под выкуп»: плановая дата и сумма.
 * Факт оплаты здесь не хранится — погашение вычисляется FIFO из суммы приходов по аренде.
 * Инвариант: сумма amount по всем строкам = buyoutPrice аренды.
 */
@Entity
@Table(name = "rental_schedule_items")
@Getter
@Setter
@NoArgsConstructor
public class RentalScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rental_id", nullable = false)
    private Rental rental;

    /** Порядковый номер платежа (1..N). */
    @Column(nullable = false)
    private int seq;

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    /** Плановая сумма платежа, ₽. */
    @Column(nullable = false)
    private int amount;

    /** Сколько из платежей отнесено на строку (FIFO при приёме оплат; перестроенные строки — 0). */
    @Column(name = "covered_amount", nullable = false)
    private int coveredAmount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
