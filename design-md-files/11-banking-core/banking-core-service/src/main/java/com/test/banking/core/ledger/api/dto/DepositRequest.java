package com.test.banking.core.ledger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DepositRequest(
        @NotBlank String accountId,
        @NotNull @Positive BigDecimal amount,
        String currency,
        String narration,
        String referenceNumber,
        String remitterIfsc,
        LocalDate valueDate) {
}
