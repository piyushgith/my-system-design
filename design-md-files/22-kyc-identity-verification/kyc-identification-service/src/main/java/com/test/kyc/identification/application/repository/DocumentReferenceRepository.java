package com.test.kyc.identification.application.repository;

import com.test.kyc.identification.application.domain.DocumentReference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentReferenceRepository extends JpaRepository<DocumentReference, UUID> {

    List<DocumentReference> findByApplicationId(UUID applicationId);

    List<DocumentReference> findByApplicationIdAndIsPurgedFalse(UUID applicationId);
}
