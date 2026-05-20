package com.test.kyc.identification.verification.repository;

import com.test.kyc.identification.verification.domain.StepType;
import com.test.kyc.identification.verification.domain.VerificationStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VerificationStepRepository extends JpaRepository<VerificationStep, UUID> {

    List<VerificationStep> findByApplicationId(UUID applicationId);

    Optional<VerificationStep> findByApplicationIdAndStepType(UUID applicationId, StepType stepType);
}
