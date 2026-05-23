package com.fintech.loan.repository;

import com.fintech.loan.domain.entity.RepaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepaymentRecordRepository extends JpaRepository<RepaymentRecord, UUID> {
    List<RepaymentRecord> findByLoanAccountIdOrderByReceivedAtDesc(UUID loanAccountId);
    Optional<RepaymentRecord> findByIdempotencyKey(String idempotencyKey);
    boolean existsByIdempotencyKey(String idempotencyKey);
}
