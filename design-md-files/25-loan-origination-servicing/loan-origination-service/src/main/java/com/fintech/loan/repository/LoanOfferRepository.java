package com.fintech.loan.repository;

import com.fintech.loan.domain.entity.LoanOffer;
import com.fintech.loan.domain.enums.LoanOfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LoanOfferRepository extends JpaRepository<LoanOffer, UUID> {
    Optional<LoanOffer> findByApplicationId(UUID applicationId);
    Optional<LoanOffer> findByApplicationIdAndStatus(UUID applicationId, LoanOfferStatus status);
}
