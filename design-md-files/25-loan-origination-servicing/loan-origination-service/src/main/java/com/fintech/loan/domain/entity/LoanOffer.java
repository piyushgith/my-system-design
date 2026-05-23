package com.fintech.loan.domain.entity;

import com.fintech.loan.domain.enums.LoanOfferStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_offers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoanOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "offer_id")
    private UUID offerId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    @Builder.Default
    private LoanOfferStatus status = LoanOfferStatus.EXTENDED;

    @Column(name = "approved_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal approvedAmount;

    // Stored as annual rate, e.g. 0.1250 = 12.5%
    @Column(name = "interest_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "tenure_months", nullable = false)
    private Integer tenureMonths;

    @Column(name = "emi_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal emiAmount;

    @Column(name = "processing_fee", precision = 18, scale = 2)
    private BigDecimal processingFee;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "disbursement_account_number", length = 18)
    private String disbursementAccountNumber;

    @Column(name = "disbursement_ifsc", length = 11)
    private String disbursementIfsc;

    @Column(name = "nach_consent")
    @Builder.Default
    private Boolean nachConsent = false;

    @Column(name = "esign_reference", length = 128)
    private String esignReference;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
