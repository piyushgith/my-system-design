package com.test.banking.core.kyc.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface KycRecordRepository extends JpaRepository<KycRecordEntity, UUID> {

    Optional<KycRecordEntity> findTopByCifIdOrderByCreatedAtDesc(String cifId);
}
