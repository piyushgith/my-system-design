package com.test.banking.core.ledger.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DepositResponse(
        String txnId,
        String status,
        String accountId,
        BigDecimal accountBalance,
        LocalDate postingDate,
        LocalDate valueDate) {
}
