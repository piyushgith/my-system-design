package com.test.banking.core.account.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LienResponse(
        UUID lienId,
        String accountId,
        BigDecimal amount,
        String reason,
        String status,
        String lienType,
        Instant createdAt) {
}
