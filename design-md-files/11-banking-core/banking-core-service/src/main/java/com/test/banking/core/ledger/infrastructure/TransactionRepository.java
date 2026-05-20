package com.test.banking.core.ledger.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    Optional<TransactionEntity> findByIdempotencyKeyAndInitiatedBy(String idempotencyKey, String initiatedBy);
}
