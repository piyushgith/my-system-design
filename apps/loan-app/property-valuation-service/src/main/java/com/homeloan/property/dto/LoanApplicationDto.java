package com.homeloan.creditcheck.dto;


import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplicationDto implements Serializable {

    private Long id;

    @NotNull(message = "Name is required")
    private String applicantName;

    @NotNull(message = "Phone number is required")
    private String applicantPhone;

    @NotNull(message = "Email is required")
    private String applicantEmail;

    @NotNull(message = "Loan Amount is required")
    private BigDecimal loanAmount;

    @NotNull(message = "ApplicationStatus is required")
    private ApplicationStatus applicationStatus;

    @NotNull(message = "propertyAddress is required")
    private String propertyAddress;

    private LocalDateTime createAt;

    private LocalDateTime updatedAt;

    public enum ApplicationStatus {
        SUBMITTED,
        CREDIT_CHECK_IN_PROGRESS,
        CREDIT_CHECK_APPROVED,
        CREDIT_CHECK_REJECTED,
        PROPERTY_VALUATION_IN_PROGRESS,
        PROPERTY_VALUATION_APPROVED,
        PROPERTY_VALUATION_REJECTED,
        DOCUMENT_VERIFICATION_IN_PROGRESS,
        DOCUMENT_VERIFICATION_APPROVED,
        DOCUMENT_VERIFICATION_REJECTED,
        LOAN_PROCESSING_IN_PROGRESS,
        LOAN_APPROVED,
        LOAN_REJECTED,
        CANCELLED
    }
}
