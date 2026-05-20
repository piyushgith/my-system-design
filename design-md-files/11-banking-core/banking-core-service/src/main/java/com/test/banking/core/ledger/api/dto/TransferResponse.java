package com.test.banking.core.ledger.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TransferResponse(
        String txnId,
        String status,
        BigDecimal fromAccountBalance,
        BigDecimal toAccountBalance,
        LocalDate postingDate,
        LocalDate valueDate) {
}
