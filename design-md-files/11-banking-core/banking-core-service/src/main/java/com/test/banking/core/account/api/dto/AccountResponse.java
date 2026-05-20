package com.test.banking.core.account.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AccountResponse(
        String accountId,
        String cifId,
        String accountType,
        String status,
        String currency,
        BigDecimal currentBalance,
        BigDecimal availableBalance,
        BigDecimal liensTotal,
        String productCode,
        LocalDate openDate,
        String kycStatus) {
}
