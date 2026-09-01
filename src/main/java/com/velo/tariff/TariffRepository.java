package com.velo.tariff;

import com.velo.rental.RentalKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<Tariff, UUID> {

    List<Tariff> findAllByModelIdOrderByUnitAscPriceAsc(UUID modelId);

    boolean existsByModelIdAndKind(UUID modelId, RentalKind kind);

    boolean existsByModelIdAndNameAndUnitAndKind(UUID modelId, String name, TariffUnit unit,
                                                 RentalKind kind);
}
