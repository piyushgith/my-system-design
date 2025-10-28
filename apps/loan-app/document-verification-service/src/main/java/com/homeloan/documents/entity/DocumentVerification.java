package com.homeloan.documents.entity;


import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "document_verifications")
public class DocumentVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "saga_id", nullable = false)
    private String sagaId;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "document_id")
    private Long documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type")
    private DocumentType documentType;

    @Column(name = "document_url")
    private String documentUrl;

    @Column(name = "verified_by", nullable = false)
    private String verifiedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false)
    private VerificationStatus verificationStatus;

    @Column(name = "verification_date", nullable = false)
    private LocalDateTime verificationDate;

    @Column(name = "comments", length = 255)
    private String comments;

    public enum VerificationStatus {
        PENDING, VERIFIED, REJECTED, INCOMPLETE
    }

    public enum DocumentType {
        ID_PROOF, ADDRESS_PROOF, INCOME_PROOF, BANK_STATEMENT, EMPLOYMENT_LETTER
    }

    @PrePersist
    protected void onCreate() {
        verificationDate = LocalDateTime.now();
        if (verificationStatus == null) verificationStatus = VerificationStatus.PENDING;
    }
}
