package com.fintech.loan.domain.entity;

import com.fintech.loan.domain.enums.LoanStatus;
import com.fintech.loan.domain.enums.ProductType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "loan_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "loan_account_id")
    private UUID loanAccountId;

    @Column(name = "loan_account_number", unique = true, nullable = false, length = 32)
    private String loanAccountNumber;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "borrower_id", nullable = false)
    private UUID borrowerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "product_type", nullable = false, length = 32)
    private ProductType productType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private LoanStatus status = LoanStatus.ACTIVE;

    @Column(name = "original_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal originalPrincipal;

    @Column(name = "outstanding_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal outstandingPrincipal;

    // Annual interest rate stored as decimal (e.g., 0.1250 = 12.5%)
    @Column(name = "interest_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "rate_type", nullable = false, length = 8)
    @Builder.Default
    private String rateType = "FIXED";

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Column(name = "remaining_tenure_months", nullable = false)
    private Integer remainingTenureMonths;

    @Column(name = "disbursed_at", nullable = false)
    private Instant disbursedAt;

    @Column(name = "first_due_date", nullable = false)
    private LocalDate firstDueDate;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "emi_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal emiAmount;

    @Column(name = "nach_mandate_id", length = 128)
    private String nachMandateId;

    @Column(name = "dpd", nullable = false)
    @Builder.Default
    private Integer dpd = 0;

    @Column(name = "npa_classified_at")
    private Instant npaClassifiedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closure_reason", length = 64)
    private String closureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
