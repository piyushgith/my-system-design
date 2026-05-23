package com.fintech.loan.dto.request;

import com.fintech.loan.domain.enums.ProductType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class SubmitApplicationRequest {

    @NotNull
    private UUID borrowerId;

    @NotNull
    private ProductType productType;

    @NotNull
    @DecimalMin("10000.00")
    @DecimalMax("10000000.00")
    private BigDecimal requestedAmount;

    @NotNull
    @Min(3)
    @Max(360)
    private Integer requestedTenureMonths;

    @Size(max = 64)
    private String purpose;

    @NotNull
    @DecimalMin("1.00")
    private BigDecimal monthlyIncome;
}
