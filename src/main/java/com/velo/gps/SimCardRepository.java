package com.velo.gps;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SimCardRepository extends JpaRepository<SimCard, UUID> {

    List<SimCard> findAllByOrderByPhoneNumber();

    boolean existsByPhoneNumber(String phoneNumber);
}
