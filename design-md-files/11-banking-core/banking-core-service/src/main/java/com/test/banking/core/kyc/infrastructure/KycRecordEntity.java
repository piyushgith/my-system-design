package com.test.banking.core.kyc.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "kyc_records", schema = "cif")
public class KycRecordEntity {

    @Id
    private UUID kycId;

    @Column(nullable = false)
    private String cifId;

    @Column(nullable = false)
    private String kycType;

    @Column(nullable = false)
    private String status;

    private String verifiedBy;
    private Instant verifiedAt;
    private LocalDate expiryDate;

    @Column(nullable = false)
    private Instant createdAt;
}
