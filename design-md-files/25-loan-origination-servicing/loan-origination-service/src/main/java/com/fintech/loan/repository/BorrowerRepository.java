package com.fintech.loan.repository;

import com.fintech.loan.domain.entity.Borrower;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BorrowerRepository extends JpaRepository<Borrower, UUID> {
    Optional<Borrower> findByExternalId(String externalId);
    Optional<Borrower> findByPanNumber(String panNumber);
    boolean existsByPanNumber(String panNumber);
}
