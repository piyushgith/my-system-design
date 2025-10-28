package com.homeloan.application.entity;


import com.homeloan.application.dto.ApplicationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;


@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
@Entity
@Table(name = "loan_applications")
public class LoanApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "applicant_name", nullable = false, length = 100)
    private String applicantName;

    @Column(name = "applicant_email", nullable = false, length = 100)
    private String applicantEmail;

    @Column(name = "applicant_phone", nullable = false, length = 20)
    private String applicantPhone;

    @Column(name = "property_address", nullable = false, length = 100)
    private String propertyAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "application_status", nullable = false, length = 100)
    private ApplicationStatus applicationStatus;

    @Column(name = "loan_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal loanAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "saga_id", nullable = false)
    private String sagaId;

    //@formatter:off
/*    public enum ApplicationStatus{
        SUBMITTED,
        CREDIT_CHECK_IN_PROGRESS,
        CREDIT_CHECK_APPROVED,
        CREDIT_CHECK_REJECTED,
        DOCUMENT_VERIFICATION_IN_PROGRESS,
        DOCUMENTS_VERIFICATION_APPROVED,
        DOCUMENTS_VERIFICATION_REJECTED,
        PROPERTY_VALUATION_IN_PROGRESS,
        PROPERTY_VALUATION_APPROVED,
        PROPERTY_VALUATION_REJECTED,
        LOAN_PROCESSING_IN_PROGRESS,
        LOAN_APPROVED,
        LOAN_REJECTED,
        CANCELLED
    }*/
    //@formatter:on

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (applicationStatus == null) {
            applicationStatus = ApplicationStatus.SUBMITTED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
