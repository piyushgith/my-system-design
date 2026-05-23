package com.fintech.loan.domain.entity;

import com.fintech.loan.domain.enums.PaymentMethod;
import com.fintech.loan.domain.enums.PaymentSource;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repayment_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RepaymentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "repayment_id")
    private UUID repaymentId;

    @Column(name = "loan_account_id", nullable = false)
    private UUID loanAccountId;

    @Column(name = "installment_number")
    private Integer installmentNumber;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "principal_paid", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalPaid;

    @Column(name = "interest_paid", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestPaid;

    @Column(name = "penalty_paid", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal penaltyPaid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 24)
    private PaymentMethod paymentMethod;

    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24)
    private PaymentSource source;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    // Prevents double application of the same payment
    @Column(name = "idempotency_key", unique = true, length = 128)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
