package com.homeloan.processing.entity;

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
@Table(name = "loan_approvals")
public class LoanApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Column(name = "approval_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ApprovalStatus approvalStatus;

    @Column(name = "approved_amount", precision = 12, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "interest_rate", precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Column(name = "loan_term_months")
    private Integer loanTermMonths;

    @Column(name = "monthly_payment", precision = 12, scale = 2)
    private BigDecimal monthlyPayment;

    @Column(name = "processed_date", nullable = false)
    private LocalDateTime processedDate;

    @Column(name = "processed_by", length = 100)
    private String processedBy;

    @Column(name = "saga_id", length = 100)
    private String sagaId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "loan_conditions", length = 1000)
    private String loanConditions;

    public enum ApprovalStatus {
        APPROVED, REJECTED, PENDING
    }

    @PrePersist
    protected void onCreate() {
        this.processedDate = LocalDateTime.now();
        if (approvalStatus == null) {
            approvalStatus = ApprovalStatus.PENDING;
        }
    }

}
