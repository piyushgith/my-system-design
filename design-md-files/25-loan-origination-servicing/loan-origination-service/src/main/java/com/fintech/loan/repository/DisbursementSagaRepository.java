package com.fintech.loan.repository;

import com.fintech.loan.domain.entity.DisbursementSaga;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DisbursementSagaRepository extends JpaRepository<DisbursementSaga, UUID> {
    boolean existsByIdempotencyKey(String idempotencyKey);
    Optional<DisbursementSaga> findByIdempotencyKey(String idempotencyKey);
}
