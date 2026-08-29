package com.velo.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FinanceAccountRepository extends JpaRepository<FinanceAccount, UUID> {

    List<FinanceAccount> findAllByOrderByCreatedAt();
}
