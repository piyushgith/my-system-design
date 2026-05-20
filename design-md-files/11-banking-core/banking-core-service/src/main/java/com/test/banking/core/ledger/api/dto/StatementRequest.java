package com.test.banking.core.ledger.api.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record StatementRequest(
        @NotNull LocalDate fromDate,
        @NotNull LocalDate toDate,
        String format) {
}
