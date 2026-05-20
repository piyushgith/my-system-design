package com.test.banking.core.account.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PlaceLienRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String reason,
        String lienType,
        String referenceId) {
}
