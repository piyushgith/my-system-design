package com.test.banking.core.account.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record BalanceResponse(
        String accountId,
        BigDecimal currentBalance,
        BigDecimal availableBalance,
        String currency,
        Instant balanceAsOf) {
}
