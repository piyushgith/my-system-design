package com.test.banking.core.ledger.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StatementLineResponse(
        LocalDate postingDate,
        String txnId,
        String entryType,
        BigDecimal amount,
        String narration) {
}
