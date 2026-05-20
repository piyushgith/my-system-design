package com.test.banking.core.kyc.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "customers", schema = "cif")
public class CustomerEntity {

    @Id
    private String cifId;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private LocalDate dateOfBirth;

    private String gender;

    @Column(nullable = false)
    private String panHash;

    private String aadhaarToken;

    @Column(nullable = false)
    private String customerType;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String riskRating;

    @Column(nullable = false)
    private boolean pepFlag;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
