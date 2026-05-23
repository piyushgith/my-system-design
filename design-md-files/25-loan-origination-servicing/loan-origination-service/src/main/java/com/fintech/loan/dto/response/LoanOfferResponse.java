package com.fintech.loan.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class LoanOfferResponse {
    private UUID offerId;
    private UUID applicationId;
    private String status;
    private BigDecimal approvedAmount;
    private BigDecimal interestRatePercent;
    private Integer tenureMonths;
    private BigDecimal emiAmount;
    private BigDecimal processingFee;
    private Instant validUntil;
    private Instant acceptedAt;
    private Instant createdAt;
}
