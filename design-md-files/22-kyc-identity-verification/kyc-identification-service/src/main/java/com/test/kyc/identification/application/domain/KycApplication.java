package com.test.kyc.identification.application.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kyc_applications")
@Getter
@Setter
@NoArgsConstructor
public class KycApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "application_id", updatable = false, nullable = false)
    private UUID applicationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_tier", nullable = false, length = 20)
    private KycTier kycTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 60)
    private KycStatus status;

    @Column(name = "parent_application_id")
    private UUID parentApplicationId;

    @Column(name = "personal_data_encrypted", nullable = false)
    private byte[] personalDataEncrypted;

    @Column(name = "personal_data_key_version", nullable = false, length = 50)
    private String personalDataKeyVersion;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "rejection_reason", length = 100)
    private String rejectionReason;

    @Column(name = "assigned_reviewer")
    private UUID assignedReviewer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "pii_expires_at", nullable = false)
    private Instant piiExpiresAt;

    @Column(name = "is_pii_purged", nullable = false)
    private boolean isPiiPurged = false;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) {
            status = KycStatus.SUBMITTED;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
