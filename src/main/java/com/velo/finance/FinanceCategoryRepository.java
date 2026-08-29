package com.velo.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FinanceCategoryRepository extends JpaRepository<FinanceCategory, UUID> {

    List<FinanceCategory> findAllByOrderByKindAscCreatedAtAsc();

    boolean existsByNameAndKind(String name, CategoryKind kind);

    Optional<FinanceCategory> findByNameAndKind(String name, CategoryKind kind);
}
