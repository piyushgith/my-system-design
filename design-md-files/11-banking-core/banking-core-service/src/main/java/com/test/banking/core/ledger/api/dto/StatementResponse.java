package com.test.banking.core.ledger.api.dto;

import java.time.LocalDate;
import java.util.List;

public record StatementResponse(
        String accountId,
        LocalDate fromDate,
        LocalDate toDate,
        String format,
        List<StatementLineResponse> lines) {
}
