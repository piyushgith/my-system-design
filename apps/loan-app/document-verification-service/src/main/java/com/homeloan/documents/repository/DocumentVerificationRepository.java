package com.homeloan.documents.repository;

import com.homeloan.documents.entity.DocumentVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentVerificationRepository extends JpaRepository<DocumentVerification, Long> {

    List<DocumentVerification> findByApplicationId(Long applicationId);

    Optional<DocumentVerification> findBySagaId(String sagaId);

    List<DocumentVerification> findByVerificationStatus(DocumentVerification.VerificationStatus status);

    List<DocumentVerification> findByVerifiedBy(String verifiedBy);

    List<DocumentVerification> findByApplicationIdOrderByVerificationDateDesc(Long applicantId);

    List<DocumentVerification> findVerifiedDocumentsByApplicationId(Long applicationId);
}
