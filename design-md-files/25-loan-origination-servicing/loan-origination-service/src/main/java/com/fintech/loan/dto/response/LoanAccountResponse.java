package com.fintech.loan.dto.response;

import com.fintech.loan.domain.enums.LoanStatus;
import com.fintech.loan.domain.enums.ProductType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class LoanAccountResponse {
    private UUID loanAccountId;
    private String loanAccountNumber;
    private UUID borrowerId;
    private ProductType productType;
    private LoanStatus status;
    private BigDecimal originalPrincipal;
    private BigDecimal outstandingPrincipal;
    private BigDecimal interestRatePercent;
    private Integer tenureMonths;
    private Integer remainingTenureMonths;
    private BigDecimal emiAmount;
    private LocalDate firstDueDate;
    private LocalDate nextDueDate;
    private Integer dpd;
    private Instant disbursedAt;
    private Instant createdAt;
}
