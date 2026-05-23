package com.fintech.loan.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "borrowers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Borrower {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "borrower_id")
    private UUID borrowerId;

    @Column(name = "external_id", unique = true, nullable = false, length = 64)
    private String externalId;

    @Column(name = "full_name", nullable = false, length = 256)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "pan_number", unique = true, length = 10)
    private String panNumber;

    @Column(name = "aadhaar_hash", length = 64)
    private String aadhaarHash;

    @Column(name = "mobile_number", length = 15)
    private String mobileNumber;

    @Column(name = "email", length = 256)
    private String email;

    @Column(name = "kyc_status", nullable = false, length = 24)
    @Builder.Default
    private String kycStatus = "PENDING";

    @Column(name = "kyc_completed_at")
    private Instant kycCompletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "is_deleted")
    @Builder.Default
    private Boolean isDeleted = false;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
