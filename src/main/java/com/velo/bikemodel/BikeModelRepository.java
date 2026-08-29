package com.velo.bikemodel;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BikeModelRepository extends JpaRepository<BikeModel, UUID> {

    List<BikeModel> findAllByOrderByBrandAscModelAsc();

    boolean existsByBrandAndModel(String brand, String model);
}
