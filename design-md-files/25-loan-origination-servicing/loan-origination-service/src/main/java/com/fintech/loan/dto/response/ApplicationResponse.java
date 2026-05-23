package com.fintech.loan.dto.response;

import com.fintech.loan.domain.enums.ApplicationStatus;
import com.fintech.loan.domain.enums.ProductType;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ApplicationResponse {
    private UUID applicationId;
    private UUID borrowerId;
    private ProductType productType;
    private ApplicationStatus status;
    private BigDecimal requestedAmount;
    private Integer requestedTenureMonths;
    private String purpose;
    private BigDecimal monthlyIncome;
    private String rejectionReason;
    private Instant submittedAt;
    private Instant decidedAt;
    private Instant createdAt;
}
