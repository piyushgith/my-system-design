package com.fintech.loan.domain.entity;

import com.fintech.loan.domain.enums.InstallmentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "amortization_schedule",
        uniqueConstraints = @UniqueConstraint(columnNames = {"loan_account_id", "installment_number"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AmortizationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_account_id", nullable = false)
    private UUID loanAccountId;

    @Column(name = "installment_number", nullable = false)
    private Integer installmentNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "opening_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingPrincipal;

    @Column(name = "emi_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal emiAmount;

    @Column(name = "principal_component", nullable = false, precision = 18, scale = 2)
    private BigDecimal principalComponent;

    @Column(name = "interest_component", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestComponent;

    @Column(name = "closing_principal", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingPrincipal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private InstallmentStatus status = InstallmentStatus.SCHEDULED;
}
