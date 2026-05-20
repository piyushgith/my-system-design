package com.test.banking.core.account.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record OpenAccountRequest(
        @NotBlank String cifId,
        @NotBlank String accountType,
        String productCode,
        @PositiveOrZero(message = "initialDeposit must be zero or positive") BigDecimal initialDeposit) {
}
