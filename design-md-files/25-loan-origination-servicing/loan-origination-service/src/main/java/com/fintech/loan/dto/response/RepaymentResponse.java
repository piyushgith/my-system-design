package com.fintech.loan.dto.response;

import com.fintech.loan.domain.enums.PaymentMethod;
import com.fintech.loan.domain.enums.PaymentSource;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class RepaymentResponse {
    private UUID repaymentId;
    private UUID loanAccountId;
    private Integer installmentNumber;
    private BigDecimal amount;
    private BigDecimal principalPaid;
    private BigDecimal interestPaid;
    private BigDecimal penaltyPaid;
    private PaymentMethod paymentMethod;
    private PaymentSource source;
    private String paymentReference;
    private Instant receivedAt;
    private Instant appliedAt;
}
