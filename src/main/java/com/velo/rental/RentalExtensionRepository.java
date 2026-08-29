package com.velo.rental;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RentalExtensionRepository extends JpaRepository<RentalExtension, UUID> {

    /** Цепочка продлений аренды в порядке создания — по ней пересчитывается срок. */
    List<RentalExtension> findAllByRentalIdOrderByCreatedAtAsc(UUID rentalId);
}
