package com.velo.tariff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TariffRepository extends JpaRepository<Tariff, UUID> {

    List<Tariff> findAllByModelIdOrderByUnitAscPriceAsc(UUID modelId);

    boolean existsByModelIdAndUnit(UUID modelId, TariffUnit unit);

    boolean existsByModelIdAndNameAndUnit(UUID modelId, String name, TariffUnit unit);
}
