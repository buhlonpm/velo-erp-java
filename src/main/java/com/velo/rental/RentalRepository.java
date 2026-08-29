package com.velo.rental;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RentalRepository extends JpaRepository<Rental, UUID> {

    int countByCustomerId(UUID customerId);

    List<Rental> findAllByOrderByCreatedAtDesc();

    /** Аренды, в позициях которых участвовал актив. */
    @Query("SELECT DISTINCT r FROM Rental r JOIN r.items i WHERE i.asset.id = :assetId ORDER BY r.createdAt DESC")
    List<Rental> findAllByAssetId(@Param("assetId") UUID assetId);
}
