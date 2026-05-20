package com.test.banking.core.ledger.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionLineResponse(
        String txnId,
        String type,
        BigDecimal amount,
        String currency,
        String narration,
        LocalDate postingDate,
        LocalDate valueDate,
        BigDecimal runningBalance) {
}
