package com.velo.gps;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GpsTrackerRepository extends JpaRepository<GpsTracker, UUID> {

    List<GpsTracker> findAllByOrderByModel();

    java.util.Optional<GpsTracker> findBySimCardId(UUID simCardId);

    List<GpsTracker> findAllByWrittenOffFromBikeId(UUID bikeId);
}
