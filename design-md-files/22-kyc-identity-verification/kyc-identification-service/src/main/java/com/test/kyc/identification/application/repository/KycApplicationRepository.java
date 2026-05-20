package com.test.kyc.identification.application.repository;

import com.test.kyc.identification.application.domain.KycApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycApplicationRepository extends JpaRepository<KycApplication, UUID> {

    Optional<KycApplication> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT a FROM KycApplication a WHERE a.userId = :userId AND a.status NOT IN ('APPROVED', 'REJECTED')")
    Optional<KycApplication> findActiveByUserId(@Param("userId") UUID userId);

    List<KycApplication> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT a FROM KycApplication a WHERE a.piiExpiresAt <= :now AND a.isPiiPurged = false ORDER BY a.piiExpiresAt ASC")
    List<KycApplication> findExpiredPiiApplications(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);
}
