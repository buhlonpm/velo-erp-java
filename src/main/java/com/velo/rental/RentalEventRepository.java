package com.velo.rental;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RentalEventRepository extends JpaRepository<RentalEvent, UUID> {

    List<RentalEvent> findAllByRentalIdOrderByDateDesc(UUID rentalId);

    /** Снять ссылку на удаляемую финансовую операцию — событие в ленте остаётся без привязки. */
    @Modifying
    @Query("UPDATE RentalEvent e SET e.transaction = null WHERE e.transaction.id = :transactionId")
    void clearTransactionReference(@Param("transactionId") UUID transactionId);

    /** Удалить всю ленту аренды (при удалении самой аренды). */
    @Modifying
    @Query("DELETE FROM RentalEvent e WHERE e.rental.id = :rentalId")
    void deleteByRentalId(@Param("rentalId") UUID rentalId);
}
