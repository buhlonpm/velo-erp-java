package com.velo.rental;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RentalExtensionRepository extends JpaRepository<RentalExtension, UUID> {

    /** Цепочка продлений аренды в порядке создания — по ней пересчитывается срок. */
    List<RentalExtension> findAllByRentalIdOrderByCreatedAtAsc(UUID rentalId);

    /** Продления сразу нескольких аренд (дашборд: «подходит к концу» от последнего отрезка). */
    List<RentalExtension> findAllByRentalIdInOrderByCreatedAtAsc(List<UUID> rentalIds);

    /** Удалить все продления аренды (при удалении самой аренды). */
    void deleteByRentalId(UUID rentalId);
}
