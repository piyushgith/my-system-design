package com.fintech.loan.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UnderwritingDecisionRequest {

    @NotNull
    private Boolean approved;

    @Size(max = 256)
    private String rejectionReason;

    // Required when approved = true
    @DecimalMin("0.0001")
    @DecimalMax("0.9999")
    private BigDecimal approvedInterestRate;

    @DecimalMin("10000.00")
    private BigDecimal approvedAmount;

    @Min(3)
    @Max(360)
    private Integer approvedTenureMonths;

    @DecimalMin("0.00")
    private BigDecimal processingFee;

    @Min(300)
    @Max(900)
    private Integer bureauScore;

    private BigDecimal dtiRatio;
}
